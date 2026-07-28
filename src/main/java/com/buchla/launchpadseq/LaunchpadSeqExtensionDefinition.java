package com.buchla.launchpadseq;

import com.bitwig.extension.api.PlatformType;
import com.bitwig.extension.controller.AutoDetectionMidiPortNamesList;
import com.bitwig.extension.controller.ControllerExtension;
import com.bitwig.extension.controller.ControllerExtensionDefinition;
import com.bitwig.extension.controller.api.ControllerHost;

import java.util.UUID;

public class LaunchpadSeqExtensionDefinition extends ControllerExtensionDefinition {
    private static final UUID DRIVER_ID = UUID.fromString("8f3e6f2a-9d3a-4b7b-8b1a-6b1a6f2d9a41");

    @Override
    public String getName() {
        return "Launchpad Step Sequencer";
    }

    @Override
    public String getAuthor() {
        return "Peter Nyboer";
    }

    @Override
    public String getVersion() {
        return "0.2.0";
    }

    @Override
    public UUID getId() {
        return DRIVER_ID;
    }

    @Override
    public String getHardwareVendor() {
        return "Novation";
    }

    @Override
    public String getHardwareModel() {
        return "Launchpad Mini MK3";
    }

    @Override
    public int getRequiredAPIVersion() {
        return 18;
    }

    @Override
    public int getNumMidiInPorts() {
        return 1;
    }

    @Override
    public int getNumMidiOutPorts() {
        return 1;
    }

    @Override
    public void listAutoDetectionMidiPortNames(final AutoDetectionMidiPortNamesList list,
                                                final PlatformType platformType) {
        final String[] inputNames = new String[1];
        final String[] outputNames = new String[1];

        switch (platformType) {
            case WINDOWS:
                // Best-effort: Windows exposes a single combined MIDI interface name for this device.
                inputNames[0] = "LPMiniMK3 MIDI";
                outputNames[0] = "LPMiniMK3 MIDI";
                break;
            case MAC:
            case LINUX:
            default:
                inputNames[0] = "Launchpad Mini MK3 LPMiniMK3 DAW Out";
                outputNames[0] = "Launchpad Mini MK3 LPMiniMK3 DAW In";
                break;
        }

        list.add(inputNames, outputNames);
    }

    @Override
    public ControllerExtension createInstance(final ControllerHost host) {
        return new LaunchpadSeqExtension(this, host);
    }
}
