package testflight;

import hudson.model.BuildListener;
import hudson.remoting.Callable;
import org.apache.commons.lang.StringUtils;

import java.io.File;
import java.io.Serializable;

/**
 * Code for sending a build to TestFlight which can run on a master or slave.
 */
public class TestflightRemoteRecorder implements Callable<Object, Throwable>, Serializable {
    final private boolean pathSpecified;
    final private TestflightUploader.UploadRequest uploadRequest;
    final private BuildListener listener;

    public TestflightRemoteRecorder(boolean pathSpecified, TestflightUploader.UploadRequest uploadRequest, BuildListener listener) {
        this.pathSpecified = pathSpecified;
        this.uploadRequest = uploadRequest;
        this.listener = listener;
    }

    public Object call() throws Throwable {
        if (!StringUtils.isEmpty(uploadRequest.dsymPath))
            uploadRequest.dsymFile = new File(uploadRequest.dsymPath);

        uploadRequest.file = identifyIpa();

        listener.getLogger().println(uploadRequest.file);

        TestflightUploader uploader = new TestflightUploader();
        return uploader.upload(uploadRequest);
    }

    private File identifyIpa() {
        if (pathSpecified) {
            return new File(uploadRequest.filePath);
        } else {
            File workspaceDir = new File(uploadRequest.filePath);
            File possibleIpa = TestflightRemoteRecorder.findIpa(workspaceDir);
            return possibleIpa != null ? possibleIpa : workspaceDir;
        }
    }

    public static File findIpa(File root) {
        for (File file : root.listFiles()) {
            if (file.isDirectory())
            {
                File ipaResult = findIpa(file);
                if(ipaResult != null)
                    return ipaResult;
            }
            else if (file.getName().endsWith(".ipa"))
            {
                return file;
            }
        }
        return null;
    }
}
