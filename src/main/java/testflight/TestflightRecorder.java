package testflight;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.model.AbstractBuild;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.Entry;
import hudson.tasks.*;
import hudson.util.RunList;
import hudson.util.Secret;
import org.apache.commons.collections.Predicate;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.*;
import net.sf.json.JSONObject;
import org.apache.commons.collections.CollectionUtils;
import org.kohsuke.stapler.StaplerRequest;

public class TestflightRecorder extends Recorder
{
    private Secret apiToken;
    public Secret getApiToken()
    {
        return this.apiToken;
    }
            
    private Secret teamToken;
    public Secret getTeamToken()
    {
        return this.teamToken;
    }
    
    private Boolean notifyTeam;
    public Boolean getNotifyTeam()
    {
        return this.notifyTeam;
    }
    
    private String buildNotes;
    public String getBuildNotes()
    {
        return this.buildNotes;
    }
    
    private boolean appendChangelog;
    public boolean getAppendChangelog()
    {
        return this.appendChangelog;
    }
    
    private String filePath;
    public String getFilePath()
    {
        return this.filePath;
    }
    
    private String dsymPath;
    public String getDsymPath()
    {
        return this.dsymPath;
    }
    
    private String lists;
    public String getLists()
    {
        return this.lists;
    }
    
    private Boolean replace;
    public Boolean getReplace()
    {
        return this.replace;
    }

    private String proxyHost;
    public String getProxyHost()
    {
        return proxyHost;
    }
    
    private String proxyUser;
    public String getProxyUser()
    {
        return proxyUser;
    }

    private String proxyPass;
    public String getProxyPass()
    {
        return proxyPass;
    }
    
    private int proxyPort;
    public int getProxyPort()
    {
        return proxyPort;
    }

    private Boolean debug;
    public Boolean getDebug()
    {
        return this.debug;
    }

    @DataBoundConstructor
    public TestflightRecorder(Secret apiToken, Secret teamToken, Boolean notifyTeam, String buildNotes, Boolean appendChangelog, String filePath, String dsymPath, String lists, Boolean replace, String proxyHost, String proxyUser, String proxyPass, int proxyPort, Boolean debug)
    {
        this.teamToken = teamToken;
        this.apiToken = apiToken;
        this.notifyTeam = notifyTeam;
        this.buildNotes = buildNotes;
        this.appendChangelog = appendChangelog;
        this.filePath = filePath;
        this.dsymPath = dsymPath;
        this.replace = replace;
        this.lists = lists;
        this.proxyHost = proxyHost;
        this.proxyUser = proxyUser;
        this.proxyPass = proxyPass;
        this.proxyPort = proxyPort;
        this.debug = debug;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    public BuildStepMonitor getRequiredMonitorService( )
    {
        return BuildStepMonitor.NONE;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, final BuildListener listener)
    {
        if (build.getResult().isWorseOrEqualTo(Result.FAILURE))
            return false;

        listener.getLogger().println(Messages.TestflightRecorder_InfoUploading());

        try
        {
            EnvVars vars = build.getEnvironment(listener);

            String workspace = vars.expand("$WORKSPACE");

            TestflightUploader.UploadRequest ur = createPartialUploadRequest(vars, build);

            TestflightRemoteRecorder remoteRecorder = new TestflightRemoteRecorder(workspace, ur, listener);

            final Map parsedMap;

            try {
                Object result = launcher.getChannel().call(remoteRecorder);
                parsedMap = (Map) result;
            } catch (UploadException ue) {
                listener.getLogger().println(Messages.TestflightRecorder_IncorrectResponseCode(ue.getStatusCode()));
                listener.getLogger().println(ue.getResponseBody());
                return false;
            }

            TestflightBuildAction installAction = new TestflightBuildAction();
            String installUrl = (String)parsedMap.get("install_url");
            installAction.displayName = Messages.TestflightRecorder_InstallLinkText();
            installAction.iconFileName = "package.gif";
            installAction.urlName = installUrl;
            build.addAction(installAction);
            listener.getLogger().println(Messages.TestflightRecorder_InfoInstallLink(installUrl));

            TestflightBuildAction configureAction = new TestflightBuildAction();
            String configUrl = (String)parsedMap.get("config_url");
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
        catch (Throwable e)
        {
            listener.getLogger().println(e);
            e.printStackTrace(listener.getLogger());
            return false;
        }

        return true;
    }

    private TestflightUploader.UploadRequest createPartialUploadRequest(EnvVars vars, AbstractBuild<?, ?> build) {
        TestflightUploader.UploadRequest ur = new TestflightUploader.UploadRequest();
        ur.filePath = vars.expand(StringUtils.trim(filePath));
        ur.dsymPath = vars.expand(StringUtils.trim(dsymPath));
        ur.apiToken = vars.expand(Secret.toString(apiToken));
        ur.buildNotes = createBuildNotes(vars.expand(buildNotes), build.getChangeSet());
        ur.lists =  vars.expand(lists);
        ur.notifyTeam = notifyTeam;
        ur.proxyHost = proxyHost;
        ur.proxyPass = proxyPass;
        ur.proxyPort = proxyPort;
        ur.proxyUser = proxyUser;
        ur.replace = replace;
        ur.teamToken = vars.expand(Secret.toString(teamToken));
        ur.debug = debug;
        return ur;
    }

    // Append the changelog if we should and can
    private String createBuildNotes(String buildNotes, final ChangeLogSet<?> changeSet) {
        if (appendChangelog) 
        {
            StringBuilder stringBuilder = new StringBuilder();
            
            // Show the build notes first
            stringBuilder.append(buildNotes);
            
            // Then append the changelog
            stringBuilder.append("\n\n")
                .append(changeSet.isEmptySet() ? Messages.TestflightRecorder_EmptyChangeSet() : Messages.TestflightRecorder_Changelog())
                .append("\n");
            
            int entryNumber = 1;
            
            for (Entry entry : changeSet) 
            {
                stringBuilder.append("\n").append(entryNumber).append(". ");
                stringBuilder.append(entry.getMsg()).append(" \u2014 ").append(entry.getAuthor());
                
                entryNumber++;
            }
            buildNotes = stringBuilder.toString();
        }
        return buildNotes;
    }

    @Override
    public Collection<? extends Action> getProjectActions(AbstractProject<?, ?> project)
    {
        ArrayList<TestflightBuildAction> actions = new ArrayList<TestflightBuildAction>();
        RunList<? extends AbstractBuild<?,?>> builds = project.getBuilds();

        Collection predicated = CollectionUtils.select(builds, new Predicate() {
            public boolean evaluate(Object o) {
				Result result  = ((AbstractBuild<?,?>)o).getResult();
				if (result == null) return false; // currently running builds can have a null result
                return result.isBetterOrEqualTo(Result.SUCCESS);
            }
        });

        ArrayList<AbstractBuild<?,?>> filteredList = new ArrayList<AbstractBuild<?,?>>(predicated);


        Collections.reverse(filteredList);
        for (AbstractBuild<?,?> build : filteredList)
        {
           List<TestflightBuildAction> testflightActions = build.getActions(TestflightBuildAction.class);
           if (testflightActions != null && testflightActions.size() > 0)
           {
               for (TestflightBuildAction action : testflightActions)
               {
                   actions.add(new TestflightBuildAction(action));
               }
               break;
           }
        }

        return actions;
    }

    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher>
    {
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
            // XXX is this now the right style?
            req.bindJSON(this,json);
            save();
            return true;
        }
                
        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return Messages.TestflightRecorder_UploadLinkText();
        }
    }

    private static class EnvAction implements EnvironmentContributingAction {
        private transient Map<String,String> data = new HashMap<String,String>();

        private void add(String key, String value) {
            if (data==null) return;
            data.put(key, value);
        }

        public void buildEnvVars(AbstractBuild<?,?> build, EnvVars env) {
            if (data!=null) env.putAll(data);
        }

        public String getIconFileName() { return null; }
        public String getDisplayName() { return null; }
        public String getUrlName() { return null; }
    }    
}
