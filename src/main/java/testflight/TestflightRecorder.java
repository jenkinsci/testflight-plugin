package testflight;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.model.AbstractBuild;
import hudson.tasks.*;
import hudson.util.RunList;
import org.apache.commons.collections.Predicate;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.*;
import net.sf.json.JSONObject;
import org.apache.commons.collections.CollectionUtils;
import org.kohsuke.stapler.StaplerRequest;

public class TestflightRecorder extends Recorder
{
    private String apiToken;
    public String getApiToken()
    {
        return this.apiToken;
    }
            
    private String teamToken;
    public String getTeamToken()
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

    @DataBoundConstructor
    public TestflightRecorder(String apiToken, String teamToken, Boolean notifyTeam, String buildNotes, String filePath, String dsymPath, String lists, Boolean replace, String proxyHost, String proxyUser, String proxyPass, int proxyPort)
    {
        this.teamToken = teamToken;
        this.apiToken = apiToken;
        this.notifyTeam = notifyTeam;
        this.buildNotes = buildNotes;
        this.filePath = filePath;
        this.dsymPath = dsymPath;
        this.replace = replace;
        this.lists = lists;
        this.proxyHost = proxyHost;
        this.proxyUser = proxyUser;
        this.proxyPass = proxyPass;
        this.proxyPort = proxyPort;
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
    public boolean perform(AbstractBuild build, Launcher launcher, final BuildListener listener)
    {
        if (build.getResult().isWorseOrEqualTo(Result.FAILURE))
            return false;

        listener.getLogger().println("Uploading to testflight");

        try
        {
            EnvVars vars = build.getEnvironment(listener);

            boolean pathSpecified = filePath != null && !filePath.trim().isEmpty();
            String expandPath;
            if(!pathSpecified)
            	expandPath = "$WORKSPACE";
            else
            	expandPath = filePath;
            
            TestflightUploader.UploadRequest ur = createPartialUploadRequest(vars, expandPath);

            TestflightRemoteRecorder remoteRecorder = new TestflightRemoteRecorder(pathSpecified, ur, listener);

            final Map parsedMap;

            try {
                Object result = launcher.getChannel().call(remoteRecorder);
                parsedMap = (Map) result;
            } catch (UploadException ue) {
                listener.getLogger().println("Incorrect response code: " + ue.getStatusCode());
                listener.getLogger().println(ue.getResponseBody());
                return false;
            }

            TestflightBuildAction installAction = new TestflightBuildAction();
            String installUrl = (String)parsedMap.get("install_url");
            installAction.displayName = "Testflight Install Link";
            installAction.iconFileName = "package.gif";
            installAction.urlName = installUrl;
            build.addAction(installAction);
            listener.getLogger().println("Testflight Install Link: " + installUrl);

            TestflightBuildAction configureAction = new TestflightBuildAction();
            String configUrl = (String)parsedMap.get("config_url");
            configureAction.displayName = "Testflight Configuration Link";
            configureAction.iconFileName = "gear2.gif";
            configureAction.urlName = configUrl;
            build.addAction(configureAction);
            listener.getLogger().println("Testflight Config Link: " + configUrl);

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

    private TestflightUploader.UploadRequest createPartialUploadRequest(EnvVars vars, String expandPath) {
        TestflightUploader.UploadRequest ur = new TestflightUploader.UploadRequest();
        ur.filePath = vars.expand(expandPath);
        ur.dsymPath = vars.expand(dsymPath);
        ur.apiToken = vars.expand(apiToken);
        ur.buildNotes = vars.expand(buildNotes);
        ur.lists =  vars.expand(lists);
        ur.notifyTeam = notifyTeam;
        ur.proxyHost = proxyHost;
        ur.proxyPass = proxyPass;
        ur.proxyPort = proxyPort;
        ur.proxyUser = proxyUser;
        ur.replace = replace;
        ur.teamToken = vars.expand(teamToken);
        return ur;
    }

    @Override
    public Collection<? extends Action> getProjectActions(AbstractProject<?, ?> project)
    {
        ArrayList<TestflightBuildAction> actions = new ArrayList<TestflightBuildAction>();
        RunList<? extends AbstractBuild<?,?>> builds = project.getBuilds();

        Collection predicated = CollectionUtils.select(builds, new Predicate() {
            public boolean evaluate(Object o) {
                return ((AbstractBuild<?,?>)o).getResult().isBetterOrEqualTo(Result.SUCCESS);
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
            return "Upload to Testflight";
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
