package testflight;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.ProxyConfiguration;
import hudson.model.*;
import hudson.model.AbstractBuild;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.Entry;
import hudson.tasks.*;
import hudson.util.CopyOnWriteList;
import hudson.util.RunList;
import hudson.util.Secret;
import org.apache.commons.collections.Predicate;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import hudson.model.Hudson;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;

import net.sf.json.JSONObject;
import org.apache.commons.collections.CollectionUtils;
import org.kohsuke.stapler.StaplerRequest;

public class TestflightRecorder extends Recorder {
    private String tokenPairName;

    public String getTokenPairName() {
        return this.tokenPairName;
    }

    private Secret apiToken;

    @Deprecated
    public Secret getApiToken() {
        return this.apiToken;
    }

    private Secret teamToken;

    @Deprecated
    public Secret getTeamToken() {
        return this.teamToken;
    }

    private Boolean notifyTeam;

    public Boolean getNotifyTeam() {
        return this.notifyTeam;
    }

    private String buildNotes;

    public String getBuildNotes() {
        return this.buildNotes;
    }

    private String buildNotesPath;

    public String getBuildNotesPath() {
        return this.buildNotesPath;
    }


    private boolean appendChangelog;

    public boolean getAppendChangelog() {
        return this.appendChangelog;
    }

    private boolean combineChangelogSinceLastSuccess;

    public boolean getCombineChangelogSinceLastSuccess() {
        return this.combineChangelogSinceLastSuccess;
    }

    /**
     * Comma- or space-separated list of patterns of files/directories to be archived.
     * The variable hasn't been renamed yet for compatibility reasons
     */
    private String filePath;

    public String getFilePath() {
        return this.filePath;
    }

    private String dsymPath;

    public String getDsymPath() {
        return this.dsymPath;
    }

    private String lists;

    public String getLists() {
        return this.lists;
    }

    private Boolean replace;

    public Boolean getReplace() {
        return this.replace;
    }

    private String proxyHost;

    @Deprecated
    public String getProxyHost() {
        return proxyHost;
    }

    private String proxyUser;

    @Deprecated
    public String getProxyUser() {
        return proxyUser;
    }

    private String proxyPass;

    @Deprecated
    public String getProxyPass() {
        return proxyPass;
    }

    private int proxyPort;

    @Deprecated
    public int getProxyPort() {
        return proxyPort;
    }

    private Boolean debug;

    public Boolean getDebug() {
        return this.debug;
    }

    private TestflightTeam[] additionalTeams;

    public TestflightTeam[] getAdditionalTeams() {
        return this.additionalTeams;
    }

    @DataBoundConstructor
    public TestflightRecorder(String tokenPairName, Secret apiToken, Secret teamToken, Boolean notifyTeam, String buildNotesPath, String buildNotes, Boolean appendChangelog, Boolean combineChangelogSinceLastSuccess, String filePath, String dsymPath, String lists, Boolean replace, String proxyHost, String proxyUser, String proxyPass, int proxyPort, Boolean debug, TestflightTeam[] additionalTeams) {
        this.tokenPairName = tokenPairName;
        this.apiToken = apiToken;
        this.teamToken = teamToken;
        this.notifyTeam = notifyTeam;
        this.buildNotes = buildNotes;
        this.buildNotesPath = buildNotesPath;
        this.appendChangelog = appendChangelog;
        this.combineChangelogSinceLastSuccess = combineChangelogSinceLastSuccess;
        this.filePath = filePath;
        this.dsymPath = dsymPath;
        this.replace = replace;
        this.lists = lists;
        this.proxyHost = proxyHost;
        this.proxyUser = proxyUser;
        this.proxyPass = proxyPass;
        this.proxyPort = proxyPort;
        this.debug = debug;
        this.additionalTeams = additionalTeams;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, final BuildListener listener) {
        if (build.getResult().isWorseOrEqualTo(Result.FAILURE))
            return false;
        listener.getLogger().println(Messages.TestflightRecorder_InfoUploading());

        try {
            EnvVars vars = build.getEnvironment(listener);

            String workspace = vars.expand("$WORKSPACE");

            List<TestflightUploader.UploadRequest> urList = new ArrayList<TestflightUploader.UploadRequest>();

            for (TestflightTeam team : createDefaultPlusAdditionalTeams()) {
                try {
                    TestflightUploader.UploadRequest ur = createPartialUploadRequest(team, vars, build);
                    urList.add(ur);
                } catch (MisconfiguredJobException mje) {
                    listener.getLogger().println(mje.getConfigurationMessage());
                    return false;
                }
            }

            for (TestflightUploader.UploadRequest ur : urList) {
                List<Entry> entries = combineChangelogSinceLastSuccess ? getChangeSetEntriesSinceLastSuccess(build) : getChangeSetEntries(build);

                TestflightRemoteRecorder remoteRecorder = new TestflightRemoteRecorder(workspace, ur, listener);

                final List<Map> parsedMaps;

                try {
                    Object result = launcher.getChannel().call(remoteRecorder);
                    parsedMaps = (List<Map>) result;
                } catch (UploadException ue) {
                    listener.getLogger().println(Messages.TestflightRecorder_IncorrectResponseCode(ue.getStatusCode()));
                    listener.getLogger().println(ue.getResponseBody());
                    return false;
                }

                if (parsedMaps.size() == 0) {
                    listener.getLogger().println(Messages.TestflightRecorder_NoUploadedFile(ur.filePaths));
                    return false;
                }
                for (Map parsedMap : parsedMaps) {
                    addTestflightLinks(build, listener, parsedMap);
                }
            }
        } catch (Throwable e) {
            listener.getLogger().println(e);
            e.printStackTrace(listener.getLogger());
            return false;
        }

        return true;
    }

    private List<TestflightTeam> createDefaultPlusAdditionalTeams() {
        List<TestflightTeam> allTeams = new ArrayList<TestflightTeam>();
        // first team is default
        allTeams.add(new TestflightTeam(getTokenPairName(), getFilePath(), getDsymPath()));
        if (additionalTeams != null) {
            allTeams.addAll(Arrays.asList(additionalTeams));
        }
        return allTeams;
    }

    private void addTestflightLinks(AbstractBuild<?, ?> build, BuildListener listener, Map parsedMap) {
        TestflightBuildAction installAction = new TestflightBuildAction();
        String installUrl = (String) parsedMap.get("install_url");
        installAction.displayName = Messages.TestflightRecorder_InstallLinkText();
        installAction.iconFileName = "package.gif";
        installAction.urlName = installUrl;
        build.addAction(installAction);
        listener.getLogger().println(Messages.TestflightRecorder_InfoInstallLink(installUrl));

        TestflightBuildAction configureAction = new TestflightBuildAction();
        String configUrl = (String) parsedMap.get("config_url");
        configureAction.displayName = Messages.TestflightRecorder_ConfigurationLinkText();
        configureAction.iconFileName = "gear2.gif";
        configureAction.urlName = configUrl;
        build.addAction(configureAction);
        listener.getLogger().println(Messages.TestflightRecorder_InfoConfigurationLink(configUrl));

        build.addAction(new EnvAction());

        // Add info about the selected build into the environment
        EnvAction envData = build.getAction(EnvAction.class);
        if (envData != null) {
            envData.add("TESTFLIGHT_INSTALL_URL", installUrl);
            envData.add("TESTFLIGHT_CONFIG_URL", configUrl);
        }
    }

    private TestflightUploader.UploadRequest createPartialUploadRequest(TestflightTeam team, EnvVars vars, AbstractBuild<?, ?> build) {
        TestflightUploader.UploadRequest ur = new TestflightUploader.UploadRequest();
        TokenPair tokenPair = getTokenPair(team.getTokenPairName());
        ur.filePaths = vars.expand(StringUtils.trim(team.getFilePath()));
        ur.dsymPath = vars.expand(StringUtils.trim(team.getDsymPath()));
        ur.apiToken = vars.expand(Secret.toString(tokenPair.getApiToken()));
        List<Entry> entries = combineChangelogSinceLastSuccess ? getChangeSetEntriesSinceLastSuccess(build) : getChangeSetEntries(build);
        File buildNotesFile = getBuildNotesFile(vars, buildNotesPath);
        ur.buildNotes = this.buildNotes;
        ur.buildNotesPath = this.buildNotesPath;
        ur.lists = vars.expand(lists);
        ur.notifyTeam = notifyTeam;
        ProxyConfiguration proxy = getProxy();
        ur.proxyHost = proxy.name;
        ur.proxyPass = proxy.getPassword();
        ur.proxyPort = proxy.port;
        ur.proxyUser = proxy.getUserName();
        ur.replace = replace;
        ur.teamToken = vars.expand(Secret.toString(tokenPair.getTeamToken()));
        ur.debug = debug;
        ur.vars = vars;
        ur.changeLog = getChangeLog(entries);
        return ur;
    }

    private String getChangeLog(List<Entry> entries) {

        String changeLog = "";
        if(appendChangelog)

        {
            StringBuilder stringBuilder = new StringBuilder();

            // Then append the changelog
            stringBuilder.append("\n\n")
              .append(entries.isEmpty() ? Messages.TestflightRecorder_EmptyChangeSet() : Messages.TestflightRecorder_Changelog())
              .append("\n");
            stringBuilder.append(changeLog);
            int entryNumber = 1;

            for (ChangeLogSet.Entry entry : entries) {
                stringBuilder.append("\n").append(entryNumber).append(". ");
                stringBuilder.append(entry.getMsg()).append(" \u2014 ").append(entry.getAuthor());

                entryNumber++;
            }
            changeLog = stringBuilder.toString();
        }
        return changeLog;
    }

    private File getBuildNotesFile(EnvVars vars, String buildNotesPath) {
        if(buildNotesPath != null && !buildNotesPath.isEmpty()) {
            buildNotesPath = vars.expand(buildNotesPath);
            File buildNotesFile = new File(buildNotesPath);
            if(buildNotesFile.exists()) {
                return buildNotesFile;
            }
            else {
                buildNotesFile = new File(vars.expand("$WORKSPACE"), buildNotesPath);
                if (buildNotesFile.exists()) {
                    return buildNotesFile;
                }
            }
        }

        File buildNotesFile = new File(vars.expand("$WORKSPACE"),"BUILD_NOTES");
        if (buildNotesFile.exists()) {
            return buildNotesFile;
        }

        return null;
    }

    private List<Entry> getChangeSetEntries(AbstractBuild<?, ?> build) {
        ArrayList<Entry> entries = new ArrayList<Entry>();
        ChangeLogSet<?> changeSet = build.getChangeSet();
        for (Entry entry : changeSet) {
            entries.add(entry);
        }
        return entries;
    }

    private List<Entry> getChangeSetEntriesSinceLastSuccess(AbstractBuild<?, ?> build) {
        ArrayList<Entry> entries = new ArrayList<Entry>();

        //The next build after the last successful one should either be a failure, or the current build.
        //It could be in progress I guess, but we should probably just append the changelog anyway.
        AbstractBuild<?, ?> lastBuild = build.getPreviousSuccessfulBuild();
        if(lastBuild != null) {
            lastBuild = lastBuild.getNextBuild();
        }
        else {
            return getChangeSetEntries(build);
        }

        while(lastBuild != null) {

            ChangeLogSet<?> changeSet = lastBuild.getChangeSet();
            if(changeSet != null) {
                for (Entry entry : changeSet) {
                    entries.add(entry);
                }
            }

            if(lastBuild.equals(build))
                break;

            lastBuild = lastBuild.getNextBuild();
        }

        return entries;
    }

    private ProxyConfiguration getProxy() {
        ProxyConfiguration proxy;
        if (Hudson.getInstance() != null && Hudson.getInstance().proxy != null) {
            proxy = Hudson.getInstance().proxy;
        } else if (proxyHost != null && proxyPort > 0) {
            // backward compatibility for pre-1.3.7 configurations
            proxy = new ProxyConfiguration(proxyHost, proxyPort, proxyUser, proxyPass);
        } else {
            proxy = new ProxyConfiguration("", 0, "", "");
        }
        return proxy;
    }

    // Append the changelog if we should and can
    private String createBuildNotes(File buildNotesFile, String buildNotes, final List<Entry> changeSet) {
        if(buildNotesFile != null) {
            try {
                String fileContents = FileUtils.readFileToString(buildNotesFile, "UTF-8");
                buildNotes = fileContents + "\n\n" + buildNotes;
            } catch (FileNotFoundException e) {
                //the file should have been checked if it exists, but if not, just ignore it.
            } catch (IOException e) {
                //ignore it
            }
        }


        if (appendChangelog) {
            StringBuilder stringBuilder = new StringBuilder();

            // Then append the changelog
            stringBuilder.append("\n\n")
                    .append(changeSet.isEmpty() ? Messages.TestflightRecorder_EmptyChangeSet() : Messages.TestflightRecorder_Changelog())
                    .append("\n");

            int entryNumber = 1;

            for (Entry entry : changeSet) {
                stringBuilder.append("\n").append(entryNumber).append(". ");
                stringBuilder.append(entry.getMsg()).append(" \u2014 ").append(entry.getAuthor());

                entryNumber++;
            }
            buildNotes = stringBuilder.toString();
        }
        return buildNotes;
    }

    @Override
    public Collection<? extends Action> getProjectActions(AbstractProject<?, ?> project) {
        ArrayList<TestflightBuildAction> actions = new ArrayList<TestflightBuildAction>();
        RunList<? extends AbstractBuild<?, ?>> builds = project.getBuilds();

        Collection predicated = CollectionUtils.select(builds, new Predicate() {
            public boolean evaluate(Object o) {
                Result result = ((AbstractBuild<?, ?>) o).getResult();
                if (result == null) return false; // currently running builds can have a null result
                return result.isBetterOrEqualTo(Result.SUCCESS);
            }
        });

        ArrayList<AbstractBuild<?, ?>> filteredList = new ArrayList<AbstractBuild<?, ?>>(predicated);


        Collections.reverse(filteredList);
        for (AbstractBuild<?, ?> build : filteredList) {
            List<TestflightBuildAction> testflightActions = build.getActions(TestflightBuildAction.class);
            if (testflightActions != null && testflightActions.size() > 0) {
                for (TestflightBuildAction action : testflightActions) {
                    actions.add(new TestflightBuildAction(action));
                }
                break;
            }
        }

        return actions;
    }

    private TokenPair getTokenPair(String tokenPairName) {
        for (TokenPair tokenPair : getDescriptor().getTokenPairs()) {
            if (tokenPair.getTokenPairName().equals(tokenPairName))
                return tokenPair;
        }

        if (getApiToken() != null && getTeamToken() != null)
            return new TokenPair("", getApiToken(), getTeamToken());

        String tokenPairNameForMessage = tokenPairName != null ? tokenPairName : "(null)";
        throw new MisconfiguredJobException(Messages._TestflightRecorder_TokenPairNotFound(tokenPairNameForMessage));
    }

    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        private final CopyOnWriteList<TokenPair> tokenPairs = new CopyOnWriteList<TokenPair>();

        public DescriptorImpl() {
            super(TestflightRecorder.class);
            load();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            tokenPairs.replaceBy(req.bindParametersToList(TokenPair.class, "tokenPair."));
            save();
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return Messages.TestflightRecorder_UploadLinkText();
        }

        public Iterable<TokenPair> getTokenPairs() {
            return tokenPairs;
        }
    }

    private static class EnvAction implements EnvironmentContributingAction {
        private transient Map<String, String> data = new HashMap<String, String>();

        private void add(String key, String value) {
            if (data == null) return;
            data.put(key, value);
        }

        public void buildEnvVars(AbstractBuild<?, ?> build, EnvVars env) {
            if (data != null) env.putAll(data);
        }

        public String getIconFileName() {
            return null;
        }

        public String getDisplayName() {
            return null;
        }

        public String getUrlName() {
            return null;
        }
    }
}
