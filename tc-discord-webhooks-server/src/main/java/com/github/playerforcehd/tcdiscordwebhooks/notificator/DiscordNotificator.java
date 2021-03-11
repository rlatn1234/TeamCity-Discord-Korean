/*
 * Copyright 2021 Pascal Zarrad
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.playerforcehd.tcdiscordwebhooks.notificator;

import com.github.playerforcehd.tcdiscordwebhooks.discord.DiscordWebHookPayload;
import com.github.playerforcehd.tcdiscordwebhooks.discord.DiscordWebHookProcessor;
import com.github.playerforcehd.tcdiscordwebhooks.discord.embeds.DiscordEmbed;
import com.github.playerforcehd.tcdiscordwebhooks.discord.embeds.DiscordEmbedColor;
import com.github.playerforcehd.tcdiscordwebhooks.discord.embeds.DiscordEmbedField;
import jetbrains.buildServer.Build;
import jetbrains.buildServer.notification.Notificator;
import jetbrains.buildServer.notification.NotificatorRegistry;
import jetbrains.buildServer.responsibility.ResponsibilityEntry;
import jetbrains.buildServer.responsibility.TestNameResponsibilityEntry;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.serverSide.comments.Comment;
import jetbrains.buildServer.serverSide.mute.MuteInfo;
import jetbrains.buildServer.serverSide.problems.BuildProblemInfo;
import jetbrains.buildServer.tests.TestName;
import jetbrains.buildServer.users.NotificatorPropertyKey;
import jetbrains.buildServer.users.PropertyKey;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.vcs.VcsRoot;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The {@link Notificator} service that handles triggered notifications
 *
 * @author Pascal Zarrad
 */
public class DiscordNotificator implements Notificator {

    /**
     * The logger used for debug messages.
     * Mostly used for error logging.
     */
    private static final Logger LOGGER = Logger.getLogger(DiscordNotificator.class);
    /**
     * The type of this {@link Notificator}
     */
    private static final String TYPE = "DiscordNotificator";
    /**
     * The display name of this notificator
     */
    private static final String DISPLAY_NAME = "Discord WebHook";
    /**
     * The name of the {@link PropertyKey} {@link #WEBHOOK_URL}
     */
    private static final String WEBHOOK_URL_KEY = "DiscordWebHookURL";
    /**
     * The name of the {@link PropertyKey} {@link #USERNAME}
     */
    private static final String WEBHOOK_USERNAME_KEY = "DiscordUsername";
    /**
     * {@link PropertyKey} of the property that defines the URL of the WebHook
     */
    private static final PropertyKey WEBHOOK_URL = new NotificatorPropertyKey(TYPE, WEBHOOK_URL_KEY);
    /**
     * {@link PropertyKey} of the property that defines the Username of the WebHook
     */
    private static final PropertyKey USERNAME = new NotificatorPropertyKey(TYPE, WEBHOOK_USERNAME_KEY);
    /**
     * The string used for situation where no data is available to display
     */
    private static final String NO_DATA = "<No data available>";
    /**
     * The {@link DiscordWebHookProcessor} that is used to trigger the WebHooks
     */
    private final DiscordWebHookProcessor discordWebHookProcessor;

    /**
     * The {@link SBuildServer} this {@link Notificator} belongs to
     */
    private SBuildServer sBuildServer;

    public DiscordNotificator(NotificatorRegistry notificatorRegistry, SBuildServer sBuildServer) {
        this.discordWebHookProcessor = new DiscordWebHookProcessor();
        this.sBuildServer = sBuildServer;
        this.initializeNotificator(notificatorRegistry);
    }

    /**
     * Creates all the {@link UserPropertyInfo}'s  and registers this {@link Notificator}
     *
     * @param notificatorRegistry The {@link NotificatorRegistry} where this {@link Notificator} will be registered to
     */
    private void initializeNotificator(NotificatorRegistry notificatorRegistry) {
        ArrayList<UserPropertyInfo> userProperties = new ArrayList<>();
        userProperties.add(new UserPropertyInfo(WEBHOOK_URL_KEY, "웹훅 URL"));
        userProperties.add(new UserPropertyInfo(WEBHOOK_USERNAME_KEY, "사용자명"));
        notificatorRegistry.register(this, userProperties);
    }

    /**
     * Send the notification by triggering the
     * {@link DiscordWebHookProcessor#sendDiscordWebHook(String, DiscordWebHookPayload)}
     * method using the data given in the discordWebHookPayload parameter.
     *
     * @param discordWebHookPayload The payload to send
     * @param users                 The users that should be notified
     */
    private void processNotify(@NotNull DiscordWebHookPayload discordWebHookPayload, @NotNull Set<SUser> users) {
        for (SUser user : users) {
            String webHookUrl = user.getPropertyValue(WEBHOOK_URL);
            String username = user.getPropertyValue(USERNAME);
            if (webHookUrl == null || webHookUrl.equals("")) {
                LOGGER.error("The Discord WebHook URL for user '" + user.getName() + "' has not been set. Can't execute the WebHook!");
                return;
            }
            if (username != null && !username.equals("")) {
                discordWebHookPayload.setUsername(username);
            }
            try {
                this.discordWebHookProcessor.sendDiscordWebHook(webHookUrl, discordWebHookPayload);
            } catch (IOException | URISyntaxException e) {
                LOGGER.error("Failed to send the WebHook!", e);
            }
        }
    }

    /**
     * Gets a Project from a {@link SRunningBuild} by searching for a project
     * that has the same project id as the running build.
     *
     * @param sRunningBuild The {@link SRunningBuild} from which the Project should be grabbed
     * @return The project that owns the build or null
     */
    private SProject getProjectFromRunningBuild(SRunningBuild sRunningBuild) {
        for (SProject project : this.sBuildServer.getProjectManager().getProjects()) {
            if (project.getProjectId().equals(sRunningBuild.getProjectId())) {
                return project;
            }
        }
        return null;
    }

    /**
     * Builds the {@link DiscordEmbedField}'s that are used in all notifications
     * that are based on an {@link SRunningBuild}.
     *
     * @param sRunningBuild The build from which the fields will be build
     * @return The {@link DiscordEmbedField}'s created from the {@link SRunningBuild}
     */
    private DiscordEmbedField[] buildFieldsForRunningBuild(SRunningBuild sRunningBuild) {
        List<DiscordEmbedField> discordEmbedFields = new ArrayList<>();
        // Grab data
        // Project
        SProject project = getProjectFromRunningBuild(sRunningBuild);
        String projectName = NO_DATA;
        if (project != null) {
            projectName = project.getName();
        }
        discordEmbedFields.add(new DiscordEmbedField("프로젝트: ", projectName, true));
        // Build name
        discordEmbedFields.add(new DiscordEmbedField("빌드:", sRunningBuild.getBuildTypeName(), true));
        // Branch
        Branch branch = sRunningBuild.getBranch();
        String branchName = "Default";
        if (branch != null && !branch.getName().equals(Branch.DEFAULT_BRANCH_NAME)) {
            branchName = branch.getDisplayName();
        }
        discordEmbedFields.add(new DiscordEmbedField("브랜치", branchName, true));
        Comment comment = sRunningBuild.getBuildComment();
        if(comment != null) {
            discordEmbedFields.add(new DiscordEmbedField("코멘트", comment.getComment(), false));
        }
        return discordEmbedFields.toArray(new DiscordEmbedField[0]);
    }

    @Override
    public void notifyBuildStarted(@NotNull SRunningBuild sRunningBuild, @NotNull Set<SUser> users) {
        String title = "빌드 시작됨!";
        String description = "ID " + sRunningBuild.getBuildId() + " 빌드가 시작되었습니다.";
        DiscordWebHookPayload discordWebHookPayload = new DiscordWebHookPayload();
        discordWebHookPayload.setEmbeds(new DiscordEmbed[]{
                new DiscordEmbed(
                        title,
                        description,
                        "",
                        DiscordEmbedColor.BLUE,
                        null,
                        null,
                        null,
                        buildFieldsForRunningBuild(sRunningBuild)
                )
        });
        this.processNotify(discordWebHookPayload, users);
    }

    @Override
    public void notifyBuildSuccessful(@NotNull SRunningBuild sRunningBuild, @NotNull Set<SUser> users) {
        String title = "빌드 성공!";
        String description = "ID " + sRunningBuild.getBuildId() + " 빌드가 성공했습니다!";
        DiscordWebHookPayload discordWebHookPayload = new DiscordWebHookPayload();
        discordWebHookPayload.setEmbeds(new DiscordEmbed[]{
                new DiscordEmbed(
                        title,
                        description,
                        "",
                        DiscordEmbedColor.GREEN,
                        null,
                        null,
                        null,
                        buildFieldsForRunningBuild(sRunningBuild)
                )
        });
        this.processNotify(discordWebHookPayload, users);
    }

    @Override
    public void notifyBuildFailed(@NotNull SRunningBuild sRunningBuild, @NotNull Set<SUser> users) {
        String title = "빌드 실패";
        String description = "ID " + sRunningBuild.getBuildId() + " 빌드가 실패했습니다.";
        DiscordWebHookPayload discordWebHookPayload = new DiscordWebHookPayload();
        discordWebHookPayload.setEmbeds(new DiscordEmbed[]{
                new DiscordEmbed(
                        title,
                        description,
                        "",
                        DiscordEmbedColor.RED,
                        null,
                        null,
                        null,
                        buildFieldsForRunningBuild(sRunningBuild)
                )
        });
        this.processNotify(discordWebHookPayload, users);
    }

    @Override
    public void notifyBuildFailedToStart(@NotNull SRunningBuild sRunningBuild, @NotNull Set<SUser> users) {
        String title = "빌드 시작 실패";
        String description = "ID " + sRunningBuild.getBuildId() + " 빌드 시작을 실패했습니다!";
        DiscordWebHookPayload discordWebHookPayload = new DiscordWebHookPayload();
        discordWebHookPayload.setEmbeds(new DiscordEmbed[]{
                new DiscordEmbed(
                        title,
                        description,
                        "",
                        DiscordEmbedColor.RED,
                        null,
                        null,
                        null,
                        buildFieldsForRunningBuild(sRunningBuild)
                )
        });
        this.processNotify(discordWebHookPayload, users);
    }

    @Override
    public void notifyLabelingFailed(@NotNull Build build, @NotNull VcsRoot vcsRoot, @NotNull Throwable throwable, @NotNull Set<SUser> users) {
        String title = "라벨링 실패";
        String description = "Labeling of build with the ID " + build.getBuildId() + " has failed!";
        DiscordWebHookPayload discordWebHookPayload = new DiscordWebHookPayload();
        discordWebHookPayload.setEmbeds(new DiscordEmbed[]{
                new DiscordEmbed(
                        title,
                        description,
                        "",
                        DiscordEmbedColor.RED,
                        null,
                        null,
                        null,
                        new DiscordEmbedField[]{}
                )
        });
        this.processNotify(discordWebHookPayload, users);
    }

    @Override
    public void notifyBuildFailing(@NotNull SRunningBuild sRunningBuild, @NotNull Set<SUser> users) {
        String title = "Build is failing";
        String description = "The build with the ID " + sRunningBuild.getBuildId() + " is failing!";
        DiscordWebHookPayload discordWebHookPayload = new DiscordWebHookPayload();
        discordWebHookPayload.setEmbeds(new DiscordEmbed[]{
                new DiscordEmbed(
                        title,
                        description,
                        "",
                        DiscordEmbedColor.RED,
                        null,
                        null,
                        null,
                        buildFieldsForRunningBuild(sRunningBuild)
                )
        });
        this.processNotify(discordWebHookPayload, users);
    }

    @Override
    public void notifyBuildProbablyHanging(@NotNull SRunningBuild sRunningBuild, @NotNull Set<SUser> users) {
        String title = "Build is probably hanging";
        String description = "The build with the ID " + sRunningBuild.getBuildId() + " is probably hanging!";
        DiscordWebHookPayload discordWebHookPayload = new DiscordWebHookPayload();
        discordWebHookPayload.setEmbeds(new DiscordEmbed[]{
                new DiscordEmbed(
                        title,
                        description,
                        "",
                        DiscordEmbedColor.ORANGE,
                        null,
                        null,
                        null,
                        buildFieldsForRunningBuild(sRunningBuild)
                )
        });
        this.processNotify(discordWebHookPayload, users);
    }

    @Override
    public void notifyResponsibleChanged(@NotNull SBuildType sBuildType, @NotNull Set<SUser> users) {
        String title = "Responsibility for build type has changed";
        String description = "The responsibility for the build type " + sBuildType.getExtendedFullName() + " has changed!";
        DiscordWebHookPayload discordWebHookPayload = new DiscordWebHookPayload();
        discordWebHookPayload.setEmbeds(new DiscordEmbed[]{
                new DiscordEmbed(
                        title,
                        description,
                        "",
                        DiscordEmbedColor.ORANGE,
                        null,
                        null,
                        null,
                        new DiscordEmbedField[]{}
                )
        });
        this.processNotify(discordWebHookPayload, users);
    }

    @Override
    public void notifyResponsibleAssigned(@NotNull SBuildType sBuildType, @NotNull Set<SUser> users) {
        String title = "Responsibility assigned";
        String description = "Responsibility for build type " + sBuildType.getExtendedFullName() + " has been assigned!";
        DiscordWebHookPayload discordWebHookPayload = new DiscordWebHookPayload();
        discordWebHookPayload.setEmbeds(new DiscordEmbed[]{
                new DiscordEmbed(
                        title,
                        description,
                        "",
                        DiscordEmbedColor.ORANGE,
                        null,
                        null,
                        null,
                        new DiscordEmbedField[]{}
                )
        });
        this.processNotify(discordWebHookPayload, users);
    }

    @Override
    public void notifyResponsibleChanged(@Nullable TestNameResponsibilityEntry testNameResponsibilityEntry, @NotNull TestNameResponsibilityEntry testNameResponsibilityEntry1, @NotNull SProject sProject, @NotNull Set<SUser> users) {
        String title = "Responsibility changed";
        String description = "Responsibility for the project " + sProject.getFullName() + " has changed!";
        DiscordWebHookPayload discordWebHookPayload = new DiscordWebHookPayload();
        discordWebHookPayload.setEmbeds(new DiscordEmbed[]{
                new DiscordEmbed(
                        title,
                        description,
                        "",
                        DiscordEmbedColor.ORANGE,
                        null,
                        null,
                        null,
                        new DiscordEmbedField[]{}
                )
        });
        this.processNotify(discordWebHookPayload, users);
    }

    @Override
    public void notifyResponsibleAssigned(@Nullable TestNameResponsibilityEntry testNameResponsibilityEntry, @NotNull TestNameResponsibilityEntry testNameResponsibilityEntry1, @NotNull SProject sProject, @NotNull Set<SUser> users) {
        String title = "Responsibility assigned";
        String description = "Responsibility for project " + sProject.getFullName() + " has been assigned!";
        DiscordWebHookPayload discordWebHookPayload = new DiscordWebHookPayload();
        discordWebHookPayload.setEmbeds(new DiscordEmbed[]{
                new DiscordEmbed(
                        title,
                        description,
                        "",
                        DiscordEmbedColor.ORANGE,
                        null,
                        null,
                        null,
                        new DiscordEmbedField[]{}
                )
        });
        this.processNotify(discordWebHookPayload, users);
    }

    @Override
    public void notifyResponsibleChanged(@NotNull Collection<TestName> collection, @NotNull ResponsibilityEntry responsibilityEntry, @NotNull SProject sProject, @NotNull Set<SUser> users) {
        String title = "Responsibility changed";
        String description = "Responsibility for project " + sProject.getFullName() + " has been changed!";
        DiscordWebHookPayload discordWebHookPayload = new DiscordWebHookPayload();
        discordWebHookPayload.setEmbeds(new DiscordEmbed[]{
                new DiscordEmbed(
                        title,
                        description,
                        "",
                        DiscordEmbedColor.ORANGE,
                        null,
                        null,
                        null,
                        new DiscordEmbedField[]{}
                )
        });
        this.processNotify(discordWebHookPayload, users);
    }

    @Override
    public void notifyResponsibleAssigned(@NotNull Collection<TestName> collection, @NotNull ResponsibilityEntry responsibilityEntry, @NotNull SProject sProject, @NotNull Set<SUser> users) {
        String title = "Responsibility assigned";
        String description = "Responsibility for one or more tests of project " + sProject.getFullName() + " have been assigned!";
        DiscordWebHookPayload discordWebHookPayload = new DiscordWebHookPayload();
        discordWebHookPayload.setEmbeds(new DiscordEmbed[]{
                new DiscordEmbed(
                        title,
                        description,
                        "",
                        DiscordEmbedColor.ORANGE,
                        null,
                        null,
                        null,
                        new DiscordEmbedField[]{}
                )
        });
        this.processNotify(discordWebHookPayload, users);
    }

    @Override
    public void notifyBuildProblemResponsibleAssigned(@NotNull Collection<BuildProblemInfo> collection, @NotNull ResponsibilityEntry responsibilityEntry, @NotNull SProject sProject, @NotNull Set<SUser> users) {
        String title = "Responsibility assigned";
        String description = "Responsibility for one or more build problems of project " + sProject.getFullName() + " have been assigned!";
        DiscordWebHookPayload discordWebHookPayload = new DiscordWebHookPayload();
        discordWebHookPayload.setEmbeds(new DiscordEmbed[]{
                new DiscordEmbed(
                        title,
                        description,
                        "",
                        DiscordEmbedColor.ORANGE,
                        null,
                        null,
                        null,
                        new DiscordEmbedField[]{}
                )
        });
        this.processNotify(discordWebHookPayload, users);
    }

    @Override
    public void notifyBuildProblemResponsibleChanged(@NotNull Collection<BuildProblemInfo> collection, @NotNull ResponsibilityEntry responsibilityEntry, @NotNull SProject sProject, @NotNull Set<SUser> users) {
        String title = "Responsibility assigned";
        String description = "Responsibility for one or more tests of project " + sProject.getFullName() + " has been changed!";
        DiscordWebHookPayload discordWebHookPayload = new DiscordWebHookPayload();
        discordWebHookPayload.setEmbeds(new DiscordEmbed[]{
                new DiscordEmbed(
                        title,
                        description,
                        "",
                        DiscordEmbedColor.ORANGE,
                        null,
                        null,
                        null,
                        new DiscordEmbedField[]{}
                )
        });
        this.processNotify(discordWebHookPayload, users);
    }

    @Override
    public void notifyTestsMuted(@NotNull Collection<STest> collection, @NotNull MuteInfo muteInfo, @NotNull Set<SUser> users) {
        String title = "Tests muted";
        if (muteInfo.getProject() != null) {
            muteInfo.getProject().getFullName();
            String description = "One or more tests of the project " + muteInfo.getProject().getFullName() + " have been muted!";
            DiscordWebHookPayload discordWebHookPayload = new DiscordWebHookPayload();
            discordWebHookPayload.setEmbeds(new DiscordEmbed[]{
                    new DiscordEmbed(
                            title,
                            description,
                            "",
                            DiscordEmbedColor.ORANGE,
                            null,
                            null,
                            null,
                            new DiscordEmbedField[]{}
                    )
            });
            this.processNotify(discordWebHookPayload, users);
        }
    }

    @Override
    public void notifyTestsUnmuted(@NotNull Collection<STest> collection, @NotNull MuteInfo muteInfo, @Nullable SUser sUser, @NotNull Set<SUser> users) {
        String title = "Tests unmuted";
        if (muteInfo.getProject() != null) {
            muteInfo.getProject().getFullName();
            String description = "One or more tests of the project " + muteInfo.getProject().getFullName() + " have been unmuted!";
            DiscordWebHookPayload discordWebHookPayload = new DiscordWebHookPayload();
            discordWebHookPayload.setEmbeds(new DiscordEmbed[]{
                    new DiscordEmbed(
                            title,
                            description,
                            "",
                            DiscordEmbedColor.ORANGE,
                            null,
                            null,
                            null,
                            new DiscordEmbedField[]{}
                    )
            });
            this.processNotify(discordWebHookPayload, users);
        }
    }

    @Override
    public void notifyBuildProblemsMuted(@NotNull Collection<BuildProblemInfo> collection, @NotNull MuteInfo muteInfo, @NotNull Set<SUser> users) {
        String title = "Build problems muted";
        if (muteInfo.getProject() != null) {
            muteInfo.getProject().getFullName();
            String description = "One or more build problems of the project " + muteInfo.getProject().getFullName() + " have been muted!";
            DiscordWebHookPayload discordWebHookPayload = new DiscordWebHookPayload();
            discordWebHookPayload.setEmbeds(new DiscordEmbed[]{
                    new DiscordEmbed(
                            title,
                            description,
                            "",
                            DiscordEmbedColor.ORANGE,
                            null,
                            null,
                            null,
                            new DiscordEmbedField[]{}
                    )
            });
            this.processNotify(discordWebHookPayload, users);
        }
    }

    @Override
    public void notifyBuildProblemsUnmuted(@NotNull Collection<BuildProblemInfo> collection, @NotNull MuteInfo muteInfo, @Nullable SUser sUser, @NotNull Set<SUser> users) {
        String title = "Build problems unmuted";
        if (muteInfo.getProject() != null) {
            muteInfo.getProject().getFullName();
            String description = "One or more build problems of the project " + muteInfo.getProject().getFullName() + " have been unmuted!";
            DiscordWebHookPayload discordWebHookPayload = new DiscordWebHookPayload();
            discordWebHookPayload.setEmbeds(new DiscordEmbed[]{
                    new DiscordEmbed(
                            title,
                            description,
                            "",
                            DiscordEmbedColor.ORANGE,
                            null,
                            null,
                            null,
                            new DiscordEmbedField[]{}
                    )
            });
            this.processNotify(discordWebHookPayload, users);
        }
    }

    @NotNull
    @Override
    public String getNotificatorType() {
        return TYPE;
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }
}
