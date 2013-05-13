package testflight;

import org.jvnet.localizer.Localizable;

public class MisconfiguredJobException extends RuntimeException {
    private Localizable configurationMessage;

    public MisconfiguredJobException(Localizable configurationMessage) {
        this.configurationMessage = configurationMessage;
    }

    public Localizable getConfigurationMessage() {
        return configurationMessage;
    }
}
