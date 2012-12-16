package testflight;

import hudson.model.BuildListener;
import hudson.remoting.Callable;

import java.io.File;
import java.io.Serializable;

/**
 * Code for sending a build to TestFlight which can run on a master or slave.
 */
public class TestflightRemoteRecorder implements Callable<Object, Throwable>, Serializable {
    final private boolean pathSpecified;
    final private boolean dsymPathSpecified;
    final private TestflightUploader.UploadRequest uploadRequest;
    final private BuildListener listener;

    public TestflightRemoteRecorder(boolean pathSpecified, boolean dsymPathSpecified, TestflightUploader.UploadRequest uploadRequest, BuildListener listener) {
        this.pathSpecified = pathSpecified;
        this.dsymPathSpecified = dsymPathSpecified;
        this.uploadRequest = uploadRequest;
        this.listener = listener;
    }

    public Object call() throws Throwable {
        uploadRequest.file = identifyIpa();
        uploadRequest.dsymFile = identifyDsym();

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

    private File identifyDsym() {
        if (dsymPathSpecified) {
            return new File(uploadRequest.dsymPath);
        } else {
            File workspaceDir = new File(uploadRequest.dsymPath);
            File possibleDsym = TestflightRemoteRecorder.findDsym(workspaceDir);
            return possibleDsym != null ? possibleDsym : null;
        }
    }

    public static File findDsym(File root) {
        for (File file : root.listFiles()) {
            if (file.isDirectory())
            {
                File dsymResult = findDsym(file);
                if(dsymResult != null)
                    return dsymResult;
            }
            else if (file.getName().endsWith("-dSYM.zip"))
            {
                return file;
            }
        }
        return null;
    }
}
