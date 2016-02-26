/*
 * Copyright 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.clouddriver.tasks.cluster

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.frigga.Names
import com.netflix.spinnaker.orca.DefaultTaskResult
import com.netflix.spinnaker.orca.ExecutionStatus
import com.netflix.spinnaker.orca.RetryableTask
import com.netflix.spinnaker.orca.TaskResult
import com.netflix.spinnaker.orca.clouddriver.OortService
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.support.Location
import com.netflix.spinnaker.orca.clouddriver.tasks.AbstractCloudProviderAwareTask
import com.netflix.spinnaker.orca.pipeline.model.Stage
import groovy.transform.Canonical
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import retrofit.RetrofitError

@Component
@Slf4j
class FindImageFromClusterTask extends AbstractCloudProviderAwareTask implements RetryableTask {

  static String SUMMARY_TYPE = "Image"

  final long backoffPeriod = 2000

  final long timeout = 60000

  static enum SelectionStrategy {
    /**
     * Choose the server group with the most instances, falling back to newest in the case of a tie
     */
    LARGEST,

    /**
     * Choose the newest ServerGroup by createdTime
     */
    NEWEST,

    /**
     * Choose the oldest ServerGroup by createdTime
     */
    OLDEST,

    /**
     * Fail if there is more than one server group to choose from
     */
    FAIL
  }

  @Value('${findImage.defaultResolveMissingLocations:false}')
  boolean defaultResolveMissingLocations = false

  @Autowired
  OortService oortService
  @Autowired
  ObjectMapper objectMapper

  @Canonical
  static class FindImageConfiguration {
    String cluster
    List<String> regions
    List<String> zones
    Boolean onlyEnabled = true
    Boolean resolveMissingLocations
    SelectionStrategy selectionStrategy = SelectionStrategy.NEWEST

    String getApplication() {
      Names.parseName(cluster).app
    }

    Set<Location> getRequiredLocations() {
        return regions?.collect { new Location(Location.Type.REGION, it) } ?:
          zones?.collect { new Location(Location.Type.ZONE, it) } ?:
            []
    }
  }

  @Override
  TaskResult execute(Stage stage) {
    String cloudProvider = getCloudProvider(stage)
    String account = getCredentials(stage)
    FindImageConfiguration config = stage.mapTo(FindImageConfiguration)
    if (config.resolveMissingLocations == null) {
      config.resolveMissingLocations = defaultResolveMissingLocations
    }

    List<Location> missingLocations = []

    Set<String> imageNames = []
    Map<Location, String> imageIds = [:]

    Map<Location, Map<String, Object>> imageSummaries = config.requiredLocations.collectEntries { location ->
      try {
        def lookupResults = oortService.getServerGroupSummary(
          config.application,
          account,
          config.cluster,
          cloudProvider,
          location.value,
          config.selectionStrategy.toString(),
          SUMMARY_TYPE,
          config.onlyEnabled.toString())
        imageNames << lookupResults.imageName
        imageIds[location] = lookupResults.imageId
        return [(location): lookupResults]
      } catch (RetrofitError e) {
        if (e.response.status == 404) {
          final Map reason
          try {
            reason = objectMapper.readValue(e.response.body.in(), new TypeReference<Map<String, Object>>() {})
          } catch (Exception ex) {
            throw new IllegalStateException("Unexpected response from API")
          }
          if (reason.error?.contains("target.fail.strategy")){
            throw new IllegalStateException("Multiple possible server groups present in ${location.value}")
          }
          if (config.resolveMissingLocations) {
            missingLocations << location
            return [(location): null]
          }

          throw new IllegalStateException("Could not find cluster '$config.cluster' for '$account' in '$location.value'.")
        }
        throw e
      }
    }

    if (missingLocations) {
      Set<String> searchNames = extractBaseImageNames(imageNames)
      if (searchNames.size() != 1) {
        throw new IllegalStateException("Request to resolve images for missing ${config.requiredLocations.first().pluralType()} requires exactly one image. (Found ${searchNames})")
      }

      def deploymentDetailTemplate = imageSummaries.find { k, v -> v != null }.value
      if (!(deploymentDetailTemplate.image && deploymentDetailTemplate.buildInfo)) {
        throw new IllegalStateException("Missing image or buildInfo on ${deploymentDetailTemplate}")
      }

      def mkDeploymentDetail = { String imageName, String imageId ->
        [
          imageId        : imageId,
          imageName      : imageName,
          serverGroupName: config.cluster,
          image          : deploymentDetailTemplate.image + [imageId: imageId, name: imageName],
          buildInfo      : deploymentDetailTemplate.buildInfo
        ]
      }

      List<Map> images = oortService.findImage(cloudProvider, searchNames[0] + '*', account, null)
      for (Map image : images) {
        for (Location location : missingLocations) {
          if (imageSummaries[location] == null && image.amis && image.amis[location.value]) {
            imageSummaries[location] = mkDeploymentDetail(image.imageName, image.amis[location.value][0])
          }
        }
      }

      def unresolved = imageSummaries.findResults { it.value == null ? it.key : null }
      if (unresolved) {
        throw new IllegalStateException("Still missing images in $unresolved.value")
      }
    }

    List<Map> deploymentDetails = imageSummaries.collect { location, summary ->
      def result = [
        ami              : summary.imageId, // TODO(ttomsu): Deprecate and remove this value.
        imageId          : summary.imageId,
        imageName        : summary.imageName,
        sourceServerGroup: summary.serverGroupName
      ]

      if (location.type == Location.Type.REGION) {
        result.region = location.value
      } else if (location.type == Location.Type.ZONE) {
        result.zone = location.value
      }

      try {
        result.putAll(summary.image ?: [:])
        result.putAll(summary.buildInfo ?: [:])
      } catch (Exception e) {
        log.error("Unable to merge server group image/build info (summary: ${summary})", e)
      }

      return result
    }

    return new DefaultTaskResult(ExecutionStatus.SUCCEEDED, [
      amiDetails: deploymentDetails
    ], [
      deploymentDetails: deploymentDetails
    ])
  }

  static Set<String> extractBaseImageNames(Collection<String> imageNames) {
    //in the case of two simultaneous bakes, the bakery tacks a counter on the end of the name
    // we want to use the base form of the name, as the search will glob out to the
    def nameCleaner = ~/(.*(?:-ebs|-s3)){1}.*/
    imageNames.findResults {
      def matcher = nameCleaner.matcher(it)
      matcher.matches() ? matcher.group(1) : null
    }.toSet()
  }
}