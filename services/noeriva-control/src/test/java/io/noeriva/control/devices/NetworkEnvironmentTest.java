package io.noeriva.control.devices;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class NetworkEnvironmentTest {
    @Test void dellParsesEnvironmentUnitsAndSumsOnlyPsuPowerColumn(){
        var sensors=new ArrayList<DeviceProtocol.Sensor>();var metrics=new HashMap<String,Double>();var facts=new HashMap<String,String>();var flags=new HashSet<String>();
        NetworkEnvironment.parse("DELL_OS9","""
            --  Fan  Status  --
            Unit Bay TrayStatus Fan1 Speed
             1 1 up up 5633
            --  Power Supplies  --
            Unit Bay Status Type FanStatus FanSpeed Power AvgPower AvgPowerStartTime
             1 1 up AC up 7264 98 88 09/08/2026-08:13
             1 2 up AC up 7312 119 99 09/08/2026-08:13
            -- Unit Environment Status --
            Unit Status Temp Voltage
            * 1 online 26C ok
            -- Optional Modules Environment Status (deg C) --
            Unit Module Temperature
            1 1 30
            -- Thermal Sensor Readings (deg C) --
            Unit CpuOnBoard Bcm56960_F SysInt Bcm56960_R
            1 26 29 25 43
            """,sensors,metrics,facts,flags);
        assertThat(flags).isEmpty();assertThat(metrics).containsEntry("power_watts",217d);
        assertThat(sensors).filteredOn(s->s.metric().equals("temperature_celsius")).hasSize(6);
        assertThat(sensors).anySatisfy(s->{assertThat(s.label()).contains("Bcm56960_R");assertThat(s.value()).isEqualTo(43d);});
        assertThat(NetworkEnvironment.health(sensors,flags)).isEqualTo("HEALTHY");
    }
    @Test void ciscoNormalAndThresholdStatesAreReadFromCliAndMilliVoltsNormalize(){
        var sensors=new ArrayList<DeviceProtocol.Sensor>();var flags=new HashSet<String>();
        NetworkEnvironment.parse("CISCO_IOS_XE","""
            Sensor List: Environmental Monitoring
             Sensor                  Location        State           Reading
             VCP 1: VX1              R0              Normal          1498 mV
             Temp: CPU Die           R0              Critical        99 Celsius
             Temp: FC                P0              Fan Speed 65%   21 Celsius
             PEM Iout                P0              Normal          6 A
            """,sensors,new HashMap<>(),new HashMap<>(),flags);
        assertThat(flags).isEmpty();assertThat(sensors).hasSize(4);
        assertThat(sensors.getFirst().value()).isEqualTo(1.498);assertThat(NetworkEnvironment.health(sensors,flags)).isEqualTo("CRITICAL");
        assertThat(DeviceObservationSummary.message(new DeviceProtocol.Reading(java.time.Instant.now(),null,"CRITICAL",Map.of(),sensors,List.of(),List.of(),List.of(),Map.of()),"SSH")).contains("CPU Die").contains("99.00 Cel");
    }
    @Test void malformedEnvironmentDoesNotReportHealthyOrPartialPowerAsTotal(){
        var sensors=new ArrayList<DeviceProtocol.Sensor>();var metrics=new HashMap<String,Double>();var flags=new HashSet<String>();
        NetworkEnvironment.parse("DELL_OS9","-- Power Supplies --\n1 1 up AC up 1000 50\n1 2 up AC up 1000 NA\n",sensors,metrics,new HashMap<>(),flags);
        assertThat(metrics).doesNotContainKey("power_watts");assertThat(flags).contains("SSH_ENVIRONMENT_VALUE_INVALID");assertThat(NetworkEnvironment.health(sensors,flags)).isEqualTo("UNKNOWN");
    }
    @Test void runningConfigurationIsSeparatelyWhitelistedAndNeverPartOfTelemetryCommands(){
        for(String profile:List.of("DELL_OS9","CISCO_IOS_XE")){
            assertThat(SshProfiles.allowedCommand(profile,"show running-config")).isTrue();
            assertThat(SshProfiles.commands(profile)).doesNotContain("show running-config");
            assertThat(SshProfiles.allowedCommand(profile,"show running-config; reload")).isFalse();
        }
        assertThat(SshProfiles.allowedCommand("HUAWEI_IMANA","show running-config")).isFalse();
    }
}
