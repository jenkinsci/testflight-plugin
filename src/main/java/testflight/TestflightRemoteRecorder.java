package testflight;

import hudson.EnvVars;
import hudson.model.BuildListener;
import hudson.remoting.Callable;
import hudson.Util;

import java.io.File;
import java.io.Serializable;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import hudson.scm.ChangeLogSet;
import org.apache.commons.lang.StringUtils;
import org.apache.tools.ant.types.FileSet;
import org.apache.commons.io.FilenameUtils;


/**
 * Code for sending a build to TestFlight which can run on a master or slave.
 * <p/>
 * When the ipa/apk file or optional dsym file are not specified, this class first tries to resolve their paths, searching them inside the workspace.
 */
public class TestflightRemoteRecorder implements Callable<Object, Throwable>, Serializable {
    final private String remoteWorkspace;
    final private TestflightUploader.UploadRequest uploadRequest;
    final private BuildListener listener;

    public TestflightRemoteRecorder(String remoteWorkspace, TestflightUploader.UploadRequest uploadRequest, BuildListener listener) {
        this.remoteWorkspace = remoteWorkspace;
        this.uploadRequest = uploadRequest;
        this.listener = listener;
    }

    public Object call() throws Throwable {
        TestflightUploader uploader = new TestflightUploader();
        if (uploadRequest.debug != null && uploadRequest.debug) {
            uploader.setLogger(new TestflightUploader.Logger() {
                public void logDebug(String message) {
                    listener.getLogger().println(message);
                }
            });
        }
        return uploadWith(uploader);
    }

    List<Map> uploadWith(TestflightUploader uploader) throws Throwable {
        List<Map> results = new ArrayList<Map>();

        Collection<File> ipaOrApkFiles = findIpaOrApkFiles(uploadRequest.filePaths);
        for (File ipaOrApkFile : ipaOrApkFiles) {
            HashMap result = new HashMap();

            TestflightUploader.UploadRequest ur = TestflightUploader.UploadRequest.copy(uploadRequest);
            boolean isIpa = ipaOrApkFile.getName().endsWith(".ipa");
            ur.file = ipaOrApkFile;
            if (isIpa) {
                ur.dsymFile = identifyDsym(ur.dsymPath, ipaOrApkFile.toString());
            }

            listener.getLogger().println("File: " + ur.file);
            if (isIpa) {
                listener.getLogger().println("DSYM: " + ur.dsymFile);
            }

            long startTime = System.currentTimeMillis();
            result.putAll(uploader.upload(ur));
            long time = System.currentTimeMillis() - startTime;

            float speed = computeSpeed(ur, time);
            listener.getLogger().println(Messages.TestflightRemoteRecorder_UploadSpeed(prettySpeed(speed)));

            results.add(result);
        }

        return results;
    }

    // return the speed in bits per second
    private float computeSpeed(TestflightUploader.UploadRequest request, long uploadTimeMillis) {
        if (uploadTimeMillis == 0) {
            return Float.NaN;
        }
        long postSize = 0;
        if (request.file != null) {
            postSize += request.file.length();
        }
        if (request.dsymFile != null) {
            postSize += request.dsymFile.length();
        }
        return (postSize * 8000.0f) / uploadTimeMillis;
    }

    static String prettySpeed(float speed) {
        if (Float.isNaN(speed)) return "NaN bps";

        String[] units = {"bps", "Kbps", "Mbps", "Gbps"};
        int idx = 0;
        while (speed > 1024 && idx <= units.length - 1) {
            speed /= 1024;
            idx += 1;
        }
        return String.format("%.2f", speed) + units[idx];
    }

    /* if a specified filePath is specified, return it, otherwise find in the workspace the DSYM matching the specified ipa file name */
    private File identifyDsym(String filePath, String ipaName) {
        File dsymFile;
        if (filePath != null && !filePath.trim().isEmpty()) {
            dsymFile = findAbsoluteOrRelativeFile(filePath);
            if (dsymFile == null)
                throw new IllegalArgumentException("Couldn't find file " + filePath + " in workspace " + remoteWorkspace);

        } else {
            String fileName = FilenameUtils.removeExtension(ipaName);
            File f = new File(fileName + "-dSYM.zip");
            if (f.exists()) {
                dsymFile = f;
            } else {
                f = new File(fileName + ".dSYM.zip");
                if (f.exists()) {
                    dsymFile = f;
                } else
                    dsymFile = null;
            }
        }
        return dsymFile;
    }

    /* if a specified filePath is specified, return it, otherwise find recursively all ipa/apk files in the remoteworkspace */
    private Collection<File> findIpaOrApkFiles(String filePaths) {
        if (StringUtils.isNotEmpty(filePaths)) {
            File absolute = findAbsoluteOrRelativeFile(filePaths);
            if (absolute != null && absolute.exists()) {
                return Arrays.asList(absolute);
            }
        } else {
            filePaths = "**/*.ipa, **/*.apk";
        }
        List<File> files = new ArrayList<File>();
        FileSet fileSet = Util.createFileSet(new File(remoteWorkspace), filePaths, null);
        Iterator it = fileSet.iterator();
        while (it.hasNext()) {
            files.add(new File(it.next().toString()));
        }
        return files;
    }

    /*
      * Finds a file that is absolute or relative to either the current direectory or the remoteWorkspace
     */
    private File findAbsoluteOrRelativeFile(String path) {
        File f = new File(path);
        if (f.exists())
            return f;
        f = new File(remoteWorkspace, path);
        if (f.exists())
            return f;
        return null;
    }
}
