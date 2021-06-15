package io.projectdiscovery.plugins.jenkins.nuclei;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import jenkins.tasks.SimpleBuildStep;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NucleiBuilder extends Builder implements SimpleBuildStep {

    /**
     * The fields must either be public or have public getters in order for Jenkins to be able to re-populate them on job configuration re-load.
     * The name of the fields must match the ones specified in <i>config.jelly</i>
     */
    private final String targetUrl;
    private String additionalFlags;
    private String reportingConfiguration;

    @DataBoundConstructor
    public NucleiBuilder(String targetUrl) {
        this.targetUrl = targetUrl;
    }

    @DataBoundSetter
    public void setAdditionalFlags(String additionalFlags) {
        this.additionalFlags = additionalFlags;
    }

    @DataBoundSetter
    public void setReportingConfiguration(String reportingConfiguration) {
        this.reportingConfiguration = reportingConfiguration;
    }

    /**
     * Getter is used by Jenkins to set the previously configured values within a job configuration.
     * Re-opening the configuration of an existing job should reload the previous values.
     */
    @SuppressWarnings("unused")
    public String getTargetUrl() {
        return targetUrl;
    }

    @SuppressWarnings("unused")
    public String getReportingConfiguration() {
        return reportingConfiguration;
    }

    @SuppressWarnings("unused")
    public String getAdditionalFlags() {
        return additionalFlags;
    }

    @Override
    public void perform(@Nonnull Run<?, ?> run, FilePath workspace, @Nonnull Launcher launcher, TaskListener listener) throws IOException {
        final PrintStream logger = listener.getLogger();
        final Path workingDirectory = Paths.get(workspace.getRemote());

        final SupportedOperatingSystem operatingSystem = SupportedOperatingSystem.getType(System.getProperty("os.name"));
        logger.println("Retrieved operating system: " + operatingSystem);

        if (workspace.isRemote()) {
            performOnSlave(run, launcher, logger, workingDirectory, operatingSystem);
        } else {
            performOnMaster(run, launcher, logger, workingDirectory, operatingSystem);
        }
    }

    private void performOnSlave(Run<?, ?> run, Launcher launcher, PrintStream logger, Path workingDirectory, SupportedOperatingSystem operatingSystem) {
        logger.println("Remote directory: " + workingDirectory);

        final VirtualChannel virtualChannel = launcher.getChannel();
        if (virtualChannel == null) {
            throw new IllegalStateException("The agent does not support remote operations!");
        }

        final FilePath filePathWorkingDirectory = new FilePath(virtualChannel, workingDirectory.toString());
        logger.println("Remote filepath directory: " + filePathWorkingDirectory);

        final RemoteNucleiDownloader remoteNucleiDownloader = new RemoteNucleiDownloader(filePathWorkingDirectory, operatingSystem);
        final List<String> cliArguments = createMandatoryCliArguments(run, launcher, logger, virtualChannel, filePathWorkingDirectory, remoteNucleiDownloader);

        addIssueTrackerConfig(cliArguments, filePathWorkingDirectory);

        final String[] resultCommand = NucleiBuilderHelper.mergeCliArguments(cliArguments, this.additionalFlags);

        NucleiBuilderHelper.runCommand(logger, launcher, resultCommand);
    }

    private void addIssueTrackerConfig(List<String> cliArguments, FilePath filePathWorkingDirectory) {
        if (this.reportingConfiguration != null && !this.reportingConfiguration.isEmpty()) {
            final FilePath reportConfigPath = NucleiBuilderHelper.resolveFilePath(filePathWorkingDirectory, "reporting_config.yml");
            try {
                reportConfigPath.write(this.reportingConfiguration, StandardCharsets.UTF_8.name());

                cliArguments.add("-report-config");
                cliArguments.add(reportConfigPath.getRemote());
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException(String.format("Error while writing the reporting/issue tracking configuration to '%s'", reportConfigPath.getRemote()));
            }
        }
    }

    private List<String> createMandatoryCliArguments(Run<?, ?> run, Launcher launcher, PrintStream logger, VirtualChannel virtualChannel, FilePath filePathWorkingDirectory, RemoteNucleiDownloader remoteNucleiDownloader) {
        try {
            final String nucleiPath = virtualChannel.call(remoteNucleiDownloader).getRemote();

            final String nucleiTemplatesPath = remoteDownloadTemplates(launcher, filePathWorkingDirectory, nucleiPath, logger);
            final FilePath outputFilePath = NucleiBuilderHelper.resolveFilePath(filePathWorkingDirectory, String.format("nucleiOutput-%s.txt", run.getId()));
            return new ArrayList<>(Arrays.asList(nucleiPath,
                                                 "-templates", nucleiTemplatesPath,
                                                 "-target", this.targetUrl,
                                                 "-output", outputFilePath.getRemote(),
                                                 "-no-color"));
        } catch (Exception e) {
            logger.println("Error while obtaining Nuclei binary");
            throw new IllegalStateException("");
        }
    }

    static String remoteDownloadTemplates(Launcher launcher, FilePath workingDirectory, String nucleiPath, PrintStream logger) {
        final String nucleiTemplatesPath = NucleiBuilderHelper.resolveFilePath(workingDirectory, "nuclei-templates").getRemote();
        NucleiBuilderHelper.runCommand(logger, launcher, new String[]{nucleiPath,
                                                                      "-update-directory", nucleiTemplatesPath,
                                                                      "-update-templates",
                                                                      "-no-color"});
        return nucleiTemplatesPath;
    }

    private void performOnMaster(Run<?, ?> run, Launcher launcher, PrintStream logger, Path workingDirectory, SupportedOperatingSystem operatingSystem) throws IOException {
        final Path nucleiPath = NucleiBuilderHelper.prepareNucleiBinary(operatingSystem, launcher, workingDirectory, logger);

        final String nucleiTemplatesPath = NucleiBuilderHelper.downloadTemplates(launcher, workingDirectory, nucleiPath, logger);

        final Path outputFilePath = workingDirectory.resolve(String.format("nucleiOutput-%s.txt", run.getId()));
        final List<String> cliArguments = new ArrayList<>(Arrays.asList(nucleiPath.toString(),
                                                                        "-templates", nucleiTemplatesPath,
                                                                        "-target", this.targetUrl,
                                                                        "-output", outputFilePath.toString(),
                                                                        "-no-color"));

        if (this.reportingConfiguration != null && !this.reportingConfiguration.isEmpty()) {
            final Path reportConfigPath = Files.write(workingDirectory.resolve("reporting_config.yml"), this.reportingConfiguration.getBytes(StandardCharsets.UTF_8),
                                                      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            cliArguments.add("-report-config");
            cliArguments.add(reportConfigPath.toString());
        }

        final String[] resultCommand = NucleiBuilderHelper.mergeCliArguments(cliArguments, this.additionalFlags);

        NucleiBuilderHelper.runCommand(logger, launcher, resultCommand);
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        /**
         * This method is called by Jenkins to validate the input fields before saving the job's configuration.
         * The name of the method must start with <b>doCheck</b> followed by the name of one of the fields declared in the <i>config.jelly</i>,
         * using standard Java naming conventions and must return {@link FormValidation}.
         * The fields intended for validation must match the name of the fields within <i>config.jelly</i> and has to be annotated with {@link QueryParameter}.
         *
         * @param targetUrl              The URL of the desired application to be tested (mandatory)
         * @param additionalFlags        Additional CLI arguments (e.g. -v -debug)
         * @param reportingConfiguration Issue tracker configuration (e.g. Jira/GitHub)
         * @return {@link FormValidation#ok()} or {@link FormValidation#error(java.lang.String)} in case of a validation error.
         */
        @SuppressWarnings("unused")
        public FormValidation doCheckTargetUrl(@QueryParameter String targetUrl, @QueryParameter String additionalFlags, @QueryParameter String reportingConfiguration) {

            if (targetUrl.isEmpty()) {
                return FormValidation.error(Messages.NucleiBuilder_DescriptorImpl_errors_missingName());
            }

            // TODO additionalFlags/reportingConfiguration validation?
            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return Messages.NucleiBuilder_DescriptorImpl_DisplayName();
        }
    }
}
