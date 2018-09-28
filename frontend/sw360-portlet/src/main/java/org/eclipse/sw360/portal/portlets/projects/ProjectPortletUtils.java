/*
 * Copyright Siemens AG, 2013-2017. Part of the SW360 Portal Project.
 *
 * SPDX-License-Identifier: EPL-1.0
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.sw360.portal.portlets.projects;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.log4j.Logger;
import org.eclipse.sw360.datahandler.common.SW360Utils;
import org.eclipse.sw360.datahandler.thrift.*;
import org.eclipse.sw360.datahandler.thrift.attachments.*;
import org.eclipse.sw360.datahandler.thrift.components.ReleaseLink;
import org.eclipse.sw360.datahandler.thrift.licenseinfo.LicenseNameWithText;
import org.eclipse.sw360.datahandler.thrift.projects.Project;
import org.eclipse.sw360.datahandler.thrift.projects.ProjectLink;
import org.eclipse.sw360.datahandler.thrift.projects.ProjectRelationship;
import org.eclipse.sw360.datahandler.thrift.users.User;
import org.eclipse.sw360.datahandler.thrift.vulnerabilities.ProjectVulnerabilityRating;
import org.eclipse.sw360.datahandler.thrift.vulnerabilities.VulnerabilityCheckStatus;
import org.eclipse.sw360.datahandler.thrift.vulnerabilities.VulnerabilityRatingForProject;
import org.eclipse.sw360.portal.common.CustomFieldHelper;
import org.eclipse.sw360.portal.common.PortalConstants;
import org.eclipse.sw360.portal.common.PortletUtils;
import org.eclipse.sw360.portal.users.UserCacheHolder;
import org.jetbrains.annotations.NotNull;

import javax.portlet.PortletRequest;
import javax.portlet.ResourceRequest;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.eclipse.sw360.datahandler.common.CommonUtils.arrayToList;
import static org.eclipse.sw360.portal.common.PortalConstants.*;

/**
 * Component portlet implementation
 *
 * @author cedric.bodet@tngtech.com
 * @author Johannes.Najjar@tngtech.com
 * @author alex.borodin@evosoft.com
 * @author thomas.maier@evosoft.com
 */
public class ProjectPortletUtils {

    private static final Logger log = Logger.getLogger(ProjectPortletUtils.class);

    private ProjectPortletUtils() {
        // Utility class with only static functions
    }

    public static void updateProjectFromRequest(PortletRequest request, Project project) {
        for (Project._Fields field : Project._Fields.values()) {
            switch (field) {
                case LINKED_PROJECTS:
                    if (!project.isSetLinkedProjects()) {
                        project.setLinkedProjects(new HashMap<>());
                    }
                    updateLinkedProjectsFromRequest(request, project.linkedProjects);
                    break;
                case RELEASE_ID_TO_USAGE:
                    if (!project.isSetReleaseIdToUsage()) {
                        project.setReleaseIdToUsage(new HashMap<>());
                    }
                    updateLinkedReleasesFromRequest(request, project.releaseIdToUsage);
                    break;

                case ATTACHMENTS:
                    project.setAttachments(PortletUtils.updateAttachmentsFromRequest(request, project.getAttachments()));
                    break;

                case LICENSE_INFO_HEADER_TEXT:
                    // if `LICENSE_INFO_HEADER_TEXT` is not in the request then we want this to be unset in the `project`
                    String licenseInfoHeader = request.getParameter(field.toString());
                    if(licenseInfoHeader == null) {
                        project.unsetLicenseInfoHeaderText();
                    } else {
                        project.setLicenseInfoHeaderText(StringEscapeUtils.unescapeHtml(licenseInfoHeader));
                    }
                    break;

                case ROLES:
                    project.setRoles(PortletUtils.getCustomMapFromRequest(request));
                    break;
                case EXTERNAL_IDS:
                    project.setExternalIds(PortletUtils.getExternalIdMapFromRequest(request));
                    break;
                default:
                    setFieldValue(request, project, field);
            }
        }
    }

    private static void updateLinkedReleasesFromRequest(PortletRequest request, Map<String, ProjectReleaseRelationship> releaseUsage) {
        releaseUsage.clear();
        String[] ids = request.getParameterValues(Project._Fields.RELEASE_ID_TO_USAGE.toString() + ReleaseLink._Fields.ID.toString());
        String[] relations = request.getParameterValues(Project._Fields.RELEASE_ID_TO_USAGE.toString() + ProjectReleaseRelationship._Fields.RELEASE_RELATION.toString());
        String[] mainlStates = request.getParameterValues(Project._Fields.RELEASE_ID_TO_USAGE.toString() + ProjectReleaseRelationship._Fields.MAINLINE_STATE.toString());
        if (ids != null && relations != null && mainlStates != null && ids.length == relations.length && ids.length == mainlStates.length) {
            for (int k = 0; k < ids.length; ++k) {
                ReleaseRelationship relation = ReleaseRelationship.findByValue(Integer.parseInt(relations[k]));
                MainlineState mainlState = MainlineState.findByValue(Integer.parseInt(mainlStates[k]));
                releaseUsage.put(ids[k], new ProjectReleaseRelationship(relation, mainlState));
            }
        }
    }

    private static void updateLinkedProjectsFromRequest(PortletRequest request, Map<String, ProjectRelationship> linkedProjects) {
        linkedProjects.clear();
        String[] ids = request.getParameterValues(Project._Fields.LINKED_PROJECTS.toString() + ProjectLink._Fields.ID.toString());
        String[] relations = request.getParameterValues(Project._Fields.LINKED_PROJECTS.toString() + ProjectLink._Fields.RELATION.toString());
        if (ids != null && relations != null && ids.length == relations.length)
            for (int k = 0; k < ids.length; ++k) {
                linkedProjects.put(ids[k], ProjectRelationship.findByValue(Integer.parseInt(relations[k])));
            }
    }

    private static void setFieldValue(PortletRequest request, Project project, Project._Fields field) {
        PortletUtils.setFieldValue(request, project, field, Project.metaDataMap.get(field), "");
    }

    public static ProjectVulnerabilityRating updateProjectVulnerabilityRatingFromRequest(Optional<ProjectVulnerabilityRating> projectVulnerabilityRatings, ResourceRequest request) throws SW360Exception {
        String projectId = request.getParameter(PortalConstants.PROJECT_ID);
        ProjectVulnerabilityRating projectVulnerabilityRating = projectVulnerabilityRatings.orElse(
                new ProjectVulnerabilityRating()
                        .setProjectId(projectId)
                        .setVulnerabilityIdToReleaseIdToStatus(new HashMap<>()));

        if (!projectVulnerabilityRating.isSetVulnerabilityIdToReleaseIdToStatus()) {
            projectVulnerabilityRating.setVulnerabilityIdToReleaseIdToStatus(new HashMap<>());
        }
        Map<String, Map<String, List<VulnerabilityCheckStatus>>> vulnerabilityIdToReleaseIdToStatus = projectVulnerabilityRating.getVulnerabilityIdToReleaseIdToStatus();

        String[] vulnerabilityIds = request.getParameterValues(PortalConstants.VULNERABILITY_IDS + "[]");
        String[] releaseIds = request.getParameterValues(PortalConstants.RELEASE_IDS + "[]");

        if (vulnerabilityIds.length != releaseIds.length) {
            String message = "Length of vulnerabilities (" + vulnerabilityIds.length + ") does not match the length of releases (" + releaseIds.length + ")!";
            log.error(message);
            throw new SW360Exception(message);
        }

        for (int i = 0; i < vulnerabilityIds.length; i++) {
            String vulnerabilityId = vulnerabilityIds[i];
            String releaseId = releaseIds[i];

            Map<String, List<VulnerabilityCheckStatus>> releaseIdToStatus = vulnerabilityIdToReleaseIdToStatus.computeIfAbsent(vulnerabilityId, k -> new HashMap<>());
            List<VulnerabilityCheckStatus> vulnerabilityCheckStatusHistory = releaseIdToStatus.computeIfAbsent(releaseId, k -> new ArrayList<>());
            VulnerabilityCheckStatus vulnerabilityCheckStatus = newVulnerabilityCheckStatusFromRequest(request);
            vulnerabilityCheckStatusHistory.add(vulnerabilityCheckStatus);
        }

        return projectVulnerabilityRating;
    }

    private static VulnerabilityCheckStatus newVulnerabilityCheckStatusFromRequest(ResourceRequest request) {
        VulnerabilityRatingForProject vulnerabilityRatingForProject = VulnerabilityRatingForProject.findByValue(
                Integer.parseInt(request.getParameter(PortalConstants.VULNERABILITY_RATING_VALUE)));

        return new VulnerabilityCheckStatus()
                .setCheckedBy(UserCacheHolder.getUserFromRequest(request).getEmail())
                .setCheckedOn(SW360Utils.getCreatedOn())
                .setComment(request.getParameter(PortalConstants.VULNERABILITY_RATING_COMMENT))
                .setVulnerabilityRating(vulnerabilityRatingForProject);
    }

    static void saveStickyProjectGroup(PortletRequest request, User user, String groupFilterValue) {
        CustomFieldHelper.saveField(request, user, CUSTOM_FIELD_PROJECT_GROUP_FILTER, groupFilterValue);
    }

    static String loadStickyProjectGroup(PortletRequest request, User user) {
        return CustomFieldHelper.loadField(String.class, request, user, CUSTOM_FIELD_PROJECT_GROUP_FILTER).orElse(null);
    }

    public static Map<String, Set<String>> getSelectedReleaseAndAttachmentIdsFromRequest(ResourceRequest request) {
        Map<String, Set<String>> releaseIdToAttachmentIds = new HashMap<>();
        String[] checkboxes = request.getParameterValues(PortalConstants.LICENSE_INFO_RELEASE_TO_ATTACHMENT);
        if (checkboxes == null) {
            return ImmutableMap.of();
        }

        Arrays.stream(checkboxes).forEach(s -> {
            String[] split = s.split(":");
            if (split.length == 2) {
                String releaseId = split[0];
                String attachmentId = split[1];
                if (!releaseIdToAttachmentIds.containsKey(releaseId)) {
                    releaseIdToAttachmentIds.put(releaseId, new HashSet<>());
                }
                releaseIdToAttachmentIds.get(releaseId).add(attachmentId);
            }
        });
        return releaseIdToAttachmentIds;
    }

    /**
     * Returns a map of excluded licenses. They key is an attachment content id, the
     * value is a list of excluded licenses.
     * <p>
     * For this method to work it is crucial that there is a so called
     * "license-store-&lt;attachmentContentId&gt;" map in the session. This map must
     * contain a mapping from a key to a {@link LicenseNameWithText} object.
     *
     * @param attachmentContentIds list of attachment content id to check for exclusions in the
     *                             request
     * @param request              the request containing the excluded licenses as parameters
     * @return a map containing the licenses to exclude
     * @see ProjectPortletUtilsTest for a better understanding
     */
    public static Map<String, Set<LicenseNameWithText>> getExcludedLicensesPerAttachmentIdFromRequest(Set<String> attachmentContentIds,
                                                                                                      ResourceRequest request) {
        Map<String, Set<LicenseNameWithText>> excludedLicenses = Maps.newHashMap();

        for (String attachmentContentId : attachmentContentIds) {
            String[] checkboxes = request.getParameterValues(attachmentContentId);
            String[] keys = request.getParameterValues(attachmentContentId + "_key");

            if (checkboxes == null) {
                // no details present
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, LicenseNameWithText> licenseStore = (Map<String, LicenseNameWithText>) request.getPortletSession()
                    .getAttribute(ProjectPortlet.LICENSE_STORE_KEY_PREFIX + attachmentContentId);
            if (licenseStore == null) {
                throw new IllegalStateException("No license store found for attachment content id " + attachmentContentId);
            }

            Set<Integer> includedIds = Arrays.stream(checkboxes).map(s -> Integer.valueOf(s)).collect(Collectors.toSet());
            Set<LicenseNameWithText> licenseNameWithTexts = Sets.newHashSet();
            for (int index = 0; index < keys.length; index++) {
                if (includedIds.contains(index)) {
                    // a request will only contain selected ids because unselected checkboxes are
                    // not transferred. Due to that we have to exclude everything that was NOT
                    // transferred
                    continue;
                }

                LicenseNameWithText licenseNameWithText = licenseStore.get(keys[index]);
                if (licenseNameWithText == null) {
                    throw new IllegalStateException("No license found for key " + keys[index]);
                }

                licenseNameWithTexts.add(licenseNameWithText);
            }

            excludedLicenses.put(attachmentContentId, licenseNameWithTexts);
        }

        return excludedLicenses;
    }

    public static List<AttachmentUsage> makeAttachmentUsages(Project project, Map<String, Set<String>> selectedReleaseAndAttachmentIds,
                                                             Function<String, UsageData> usageDataGenerator) {
        List<AttachmentUsage> attachmentUsages = Lists.newArrayList();

        for(String releaseId : selectedReleaseAndAttachmentIds.keySet()) {
            for(String attachmentContentId : selectedReleaseAndAttachmentIds.get(releaseId)) {
                AttachmentUsage usage = new AttachmentUsage();
                usage.setUsedBy(Source.projectId(project.getId()));
                usage.setOwner(Source.releaseId(releaseId));
                usage.setAttachmentContentId(attachmentContentId);

                UsageData usageData = usageDataGenerator.apply(attachmentContentId);
                usage.setUsageData(usageData);

                attachmentUsages.add(usage);
            }
        }

        return attachmentUsages;
    }

    /**
     * Walks through a list of project links and extracts all release attachments
     * with their owner. The returned map is a mapping from a release to its
     * attachment content ids.
     *
     * @param projectLinks
     *            list of project links to walk through
     *
     * @return map of releases and their attachment content ids
     */
    public static Map<Source, Set<String>> extractContainedAttachments(Collection<ProjectLink> projectLinks) {
        Map<Source, Set<String>> attachments = Maps.newHashMap();

        for (ProjectLink projectLink : projectLinks) {
            for (ReleaseLink releaseLink : projectLink.linkedReleases) {
                Set<String> attachmentIds = attachments.getOrDefault(Source.releaseId(releaseLink.getId()), Sets.newHashSet());
                attachmentIds
                        .addAll(releaseLink.getAttachments().stream().map(a -> a.getAttachmentContentId()).collect(Collectors.toList()));
                attachments.put(Source.releaseId(releaseLink.getId()), attachmentIds);
            }
        }

        return attachments;
    }

    public static List<AttachmentUsage> deselectedAttachmentUsagesFromRequest(ResourceRequest request) {
        return makeAttachmentUsagesFromRequestParameters(request, Sets::difference);
    }

    public static List<AttachmentUsage> selectedAttachmentUsagesFromRequest(ResourceRequest request) {
        return makeAttachmentUsagesFromRequestParameters(request, Sets::intersection);
    }

    private static List<AttachmentUsage> makeAttachmentUsagesFromRequestParameters(ResourceRequest request, BiFunction<Set<String>, Set<String>, Set<String>> computeUsagesFromCheckboxes) {
        final String projectId = request.getParameter(PROJECT_ID);
        Set<String> selectedUsages = new HashSet<>(arrayToList(request.getParameterValues(PROJECT_SELECTED_ATTACHMENT_USAGES)));
        Set<String> changedUsages = new HashSet<>(arrayToList(request.getParameterValues(PROJECT_SELECTED_ATTACHMENT_USAGES_SHADOWS)));
        Set<String> usagesSubset = computeUsagesFromCheckboxes.apply(changedUsages, selectedUsages);
        return usagesSubset.stream()
                .map(s -> parseAttachmentUsageFromString(projectId, s))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private static AttachmentUsage parseAttachmentUsageFromString(String projectId, String s) {
        String[] split = s.split("_");
        if (split.length != 3) {
            log.warn(String.format("cannot parse attachment usage from %s for project id %s", s, projectId));
            return null;
        }

        String releaseId = split[0];
        String type = split[1];
        String attachmentContentId = split[2];

        AttachmentUsage usage = new AttachmentUsage(Source.releaseId(releaseId), attachmentContentId, Source.projectId(projectId));
        final UsageData usageData;
        switch (UsageData._Fields.findByName(type)) {
            case LICENSE_INFO:
                usageData = UsageData.licenseInfo(new LicenseInfoUsage(Collections.emptySet()));
                break;
            case SOURCE_PACKAGE:
                usageData = UsageData.sourcePackage(new SourcePackageUsage());
                break;
            case MANUALLY_SET:
                usageData = UsageData.manuallySet(new ManuallySetUsage());
                break;
            default:
                throw new IllegalArgumentException("Unexpected UsageData type: " + type);
        }
        usage.setUsageData(usageData);
        return usage;
    }

    /**
     * Here, "equivalent" means an AttachmentUsage should replace another one in the DB, not that they are equal.
     * I.e, they have the same attachmentContentId, owner, usedBy, and same UsageData type.
     */
    @NotNull
    static Predicate<AttachmentUsage> isUsageEquivalent(AttachmentUsage usage) {
        return equivalentUsage -> usage.getAttachmentContentId().equals(equivalentUsage.getAttachmentContentId()) &&
                usage.getOwner().equals(equivalentUsage.getOwner()) &&
                usage.getUsedBy().equals(equivalentUsage.getUsedBy()) &&
                usage.getUsageData().getSetField().equals(equivalentUsage.getUsageData().getSetField());
    }

    static AttachmentUsage mergeAttachmentUsages(AttachmentUsage u1, AttachmentUsage u2) {
        if (u1.getUsageData() == null) {
            if (u2.getUsageData() == null) {
                return u1;
            } else {
                throw new IllegalArgumentException("Cannot merge attachment usages of different usage types");
            }
        } else {
            if (!u1.getUsageData().getSetField().equals(u2.getUsageData().getSetField())) {
                throw new IllegalArgumentException("Cannot merge attachment usages of different usage types");
            }
        }
        AttachmentUsage mergedUsage = u1.deepCopy();
        switch (u1.getUsageData().getSetField()) {
            case LICENSE_INFO:
                mergedUsage.getUsageData().getLicenseInfo().setExcludedLicenseIds(
                        Sets.union(Optional.of(u1)
                                        .map(AttachmentUsage::getUsageData)
                                        .map(UsageData::getLicenseInfo)
                                        .map(LicenseInfoUsage::getExcludedLicenseIds)
                                        .orElse(Collections.emptySet()),
                                Optional.of(u2)
                                        .map(AttachmentUsage::getUsageData)
                                        .map(UsageData::getLicenseInfo)
                                        .map(LicenseInfoUsage::getExcludedLicenseIds)
                                        .orElse(Collections.emptySet())));
                break;
            case SOURCE_PACKAGE:
            case MANUALLY_SET:
                // do nothing
                // source package and manual usages do not have any information to be merged
                break;
            default:
                throw new IllegalArgumentException("Unexpected UsageData type: " + u1.getUsageData().getSetField());
        }

        return mergedUsage;
    }
}
