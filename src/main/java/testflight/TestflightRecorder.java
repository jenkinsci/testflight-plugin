package testflight;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.model.AbstractBuild;
import hudson.tasks.*;
import hudson.util.RunList;
import org.apache.commons.collections.Predicate;
import org.kohsuke.stapler.DataBoundConstructor;
import java.io.*;
import java.util.*;
import net.sf.json.JSONObject;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
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
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener)
    {
        if (build.getResult().isWorseOrEqualTo(Result.FAILURE))
            return false;

        listener.getLogger().println("Uploading to testflight");

        File tempDir = null;
        try
        {
            EnvVars vars = build.getEnvironment(listener);

            // Copy remote file to local file system.
            tempDir = File.createTempFile("jtf", null);
            tempDir.delete();
            tempDir.mkdirs();
            
            boolean pathSpecified = filePath != null && !filePath.trim().isEmpty();
            String expandPath;
            if(!pathSpecified)
            	expandPath = "$WORKSPACE";
            else
            	expandPath = filePath;
            
            File file = getFileLocally(build.getWorkspace(), vars.expand(expandPath), tempDir, pathSpecified);
            listener.getLogger().println(file);
            
            File dsymFile = null;
            if (!StringUtils.isEmpty(dsymPath)) {
                dsymFile = getFileLocally(build.getWorkspace(), vars.expand(dsymPath), tempDir, true);
            }

            TestflightUploader uploader = new TestflightUploader();
            TestflightUploader.UploadRequest ur = createUploadRequest(file, dsymFile, vars);

            final Map parsedMap;
            try {
                parsedMap = uploader.upload(ur);
            } catch (UploadException ue) {
                listener.getLogger().println("Incorrect response code: " + ue.getStatusCode());
                listener.getLogger().println(ue.getResponseBody());
                return false;
            }

            TestflightBuildAction installAction = new TestflightBuildAction();
            installAction.displayName = "Testflight Install Link";
            installAction.iconFileName = "package.gif";
            installAction.urlName = (String)parsedMap.get("install_url");
            build.addAction(installAction);

            TestflightBuildAction configureAction = new TestflightBuildAction();
            configureAction.displayName = "Testflight Configuration Link";
            configureAction.iconFileName = "gear2.gif";
            configureAction.urlName = (String)parsedMap.get("config_url");
            build.addAction(configureAction);
        }
        catch (Exception e)
        {
            listener.getLogger().println(e);
            e.printStackTrace(listener.getLogger());
            return false;
        }
        finally
        {
            try
            {
                FileUtils.deleteDirectory(tempDir);
            }
            catch (IOException e)
            {
                try
                {
                    FileUtils.forceDeleteOnExit(tempDir);
                }
                catch (IOException e1)
                {
                    listener.getLogger().println(e1);
                }
            }
        }

        return true;
    }

    private TestflightUploader.UploadRequest createUploadRequest(File file, File dsymFile, EnvVars vars) {
        TestflightUploader.UploadRequest ur = new TestflightUploader.UploadRequest();
        ur.apiToken = apiToken;
        ur.buildNotes = vars.expand(buildNotes);
        ur.dsymFile = dsymFile;
        ur.file = file;
        ur.lists = lists;
        ur.notifyTeam = notifyTeam;
        ur.proxyHost = proxyHost;
        ur.proxyPass = proxyPass;
        ur.proxyPort = proxyPort;
        ur.proxyUser = proxyUser;
        ur.replace = replace;
        ur.teamToken = teamToken;
        return ur;
    }

    private File getFileLocally(FilePath workingDir, String strFile, File tempDir, boolean pathSpecified) throws IOException, InterruptedException
    {
    	if(!pathSpecified) {
    		File workspaceDir = new File(strFile);
    		List<File> ipas = new LinkedList<File>();
    		findIpas(workspaceDir, ipas);
    		if(ipas.isEmpty())
    			return workspaceDir;
    		return ipas.get(0);
    	} else {
			if (workingDir.isRemote())
			{
				FilePath remoteFile = new FilePath(workingDir, strFile);
				File file = new File(tempDir, remoteFile.getName());
				file.createNewFile();
				FileOutputStream fos = new FileOutputStream(file);
				remoteFile.copyTo(fos);
				fos.close();
				return file;
			}
			else
			{
				return new File(strFile);
			}
        }
    }
    
    private void findIpas(File root, List<File> ipas) {
		for(File file : root.listFiles()) {
			if(file.isDirectory())
				findIpas(file, ipas);
			else if(file.getName().endsWith(".ipa"))
				ipas.add(file);
		}
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
}
