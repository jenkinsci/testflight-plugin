package testflight;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.FilePath.FileCallable;
import hudson.Launcher;
import hudson.model.*;
import hudson.model.AbstractBuild;
import hudson.remoting.VirtualChannel;
import hudson.tasks.*;
import hudson.util.DirScanner;
import hudson.util.FileVisitor;
import hudson.util.RunList;
import org.apache.commons.collections.Predicate;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.simple.parser.JSONParser;
import org.kohsuke.stapler.DataBoundConstructor;
import java.io.*;
import java.util.*;
import org.apache.http.auth.Credentials;
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
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException
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
            
            String fileStr;
            if (this.filePath == null || this.filePath.trim().isEmpty())
            {
                fileStr = "**/*.ipa";
            }
            else
            {
                fileStr = vars.expand(this.filePath);
            }
            FilePath filePath = getFilePath(build.getWorkspace(), vars.expand(fileStr));
            if (filePath == null)
            {
                throw new hudson.AbortException("Unable to find .ipa file. Looking for: " + fileStr);
            }
            File file = getFileLocally(filePath, tempDir);
            listener.getLogger().println(file);

            DefaultHttpClient httpClient = new DefaultHttpClient();

            // Configure the proxy if necessary
            if(proxyHost!=null && !proxyHost.isEmpty() && proxyPort>0) {
                Credentials cred = null;
                if(proxyUser!=null && !proxyUser.isEmpty())
                    cred = new UsernamePasswordCredentials(proxyUser, proxyPass);

                httpClient.getCredentialsProvider().setCredentials(new AuthScope(proxyHost, proxyPort),cred);
                HttpHost proxy = new HttpHost(proxyHost, proxyPort);
                httpClient.getParams().setParameter( ConnRoutePNames.DEFAULT_PROXY, proxy);
            }

            HttpHost targetHost = new HttpHost("testflightapp.com");
            HttpPost httpPost = new HttpPost("/api/builds.json");
            FileBody fileBody = new FileBody(file);
            
            MultipartEntity entity = new MultipartEntity();
            entity.addPart("api_token", new StringBody(apiToken));
            entity.addPart("team_token", new StringBody(teamToken));
            entity.addPart("notes", new StringBody(vars.expand(buildNotes)));
            entity.addPart("file", fileBody);
            
            if (!StringUtils.isEmpty(dsymPath)) {
                String dsymFileStr = vars.expand(this.dsymPath);
                FilePath dsymFilePath = getFilePath(build.getWorkspace(), dsymFileStr);
                if (dsymFilePath == null)
                {
                    throw new hudson.AbortException("Unable to find .dsym file. Looking for: " + dsymFileStr);
                }
                File dsymFile = getFileLocally(dsymFilePath, tempDir);
                listener.getLogger().println(dsymFile);
                FileBody dsymFileBody = new FileBody(dsymFile);
                entity.addPart("dsym", dsymFileBody);
            }
            
            if (lists.length() > 0)
                entity.addPart("distribution_lists", new StringBody(lists));
            entity.addPart("notify", new StringBody(notifyTeam ? "True" : "False"));
            entity.addPart("replace", new StringBody(replace ? "True" : "False"));
            httpPost.setEntity(entity);

            HttpResponse response = httpClient.execute(targetHost,httpPost);
            HttpEntity resEntity = response.getEntity();

            InputStream is = resEntity.getContent();

            // Improved error handling.
            if (response.getStatusLine().getStatusCode() != 200) {
                String responseBody = new Scanner(is).useDelimiter("\\A").next();
                listener.getLogger().println("Incorrect response code: " + response.getStatusLine().getStatusCode());
                listener.getLogger().println(responseBody);
                return false;
            }

            JSONParser parser = new JSONParser();

            final Map parsedMap = (Map)parser.parse(new BufferedReader(new InputStreamReader(is)));

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
        catch (InterruptedException e)
        {
            throw e;
        }
        catch (IOException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            e.printStackTrace(listener.getLogger());
            throw new hudson.AbortException("Exception while uploading to Testflight.");
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
    
    private FilePath getFilePath(FilePath workingDir, String globStr) throws IOException, InterruptedException
    {
        List<FilePath> matchingFiles = findFiles(workingDir, globStr);
        if (matchingFiles.isEmpty())
        {
            return null;
        }
        else
        {
            return matchingFiles.get(0);
        }
    }

    private List<FilePath> findFiles(final FilePath root, final String globStr) throws IOException, InterruptedException
    {
        return root.act(new RemoteFileFinder(globStr));
    }
    
    private static class RemoteFileFinder implements FileCallable<List<FilePath>>, Serializable{
        private static final long serialVersionUID = 4077012804269527387L;
        private String globStr;
        public RemoteFileFinder(String globStr)
        {
            this.globStr = globStr;
        }

        public List<FilePath> invoke(File f, VirtualChannel channel) throws IOException, InterruptedException
        {
            LinkedList<FilePath> matchingFiles = new LinkedList<FilePath>();
            
            // Special Case: Check if the string specified was a single file
            File absFile = new File(globStr);
            if (absFile.exists())
            {
                matchingFiles.add(new FilePath(absFile));
                return matchingFiles;
            }
            
            ListVisitor listVisitor = new ListVisitor(matchingFiles);
            DirScanner.Glob globScanner = new DirScanner.Glob(globStr, null);
            globScanner.scan(f, listVisitor);
            return matchingFiles;
        }
    }
    
    private static class ListVisitor extends FileVisitor implements Serializable{
        private static final long serialVersionUID = 4599853329698643552L;
        private LinkedList<FilePath> matchingFiles;
        public ListVisitor(LinkedList<FilePath> matchingFiles)
        {
            this.matchingFiles = matchingFiles;
        }

        @Override
        public void visit(File f, String relativePath) throws IOException
        {
            matchingFiles.add(new FilePath(f));
        }
    }
    
    private File getFileLocally(FilePath remoteFile, File tempDir) throws IOException, InterruptedException
    {
        if (remoteFile.isRemote())
        {
            File file = new File(tempDir, remoteFile.getName());
            file.createNewFile();
            FileOutputStream fos = new FileOutputStream(file);
            remoteFile.copyTo(fos);
            fos.close();
            return file;
        }
        else
        {
            return new File(remoteFile.absolutize().getRemote());
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
