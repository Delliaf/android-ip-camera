package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.*
import org.junit.Test

/**
 * Real SOAP operations through the ONVIF handler. Tests a control bridge that
 * records exactly what was forwarded, not only the static XML response string.
 */
class OnvifControlIntegrationTest {
    private val commands = mutableListOf<Pair<String, String>>()
    private var status = OnvifControlStatus(
        focusAvailable = true, focusLocked = true, focusDiopters = 2.5f,
        maxFocusDiopters = 10f, torchAvailable = true, torchEnabled = false,
        batteryPercent = 78, batteryCelsius = 35.6, socCelsius = 54.2, cpuPercent = 29)
    private var bitrate = 6000
    private var fps = 30

    private fun server(accepted: Boolean = true) = OnvifServer(
        port = 0, host = { "127.0.0.1" },
        info = { OnvifDeviceInfo("LG", "LG-H872", "0.18", "serial", "device", "mac", "camera0") },
        stream = {
            OnvifStreamInfo("rtsp://127.0.0.1:8554/live", 960, 720, fps, bitrate,
                quality = 3,
                subWidth = 480, subHeight = 360, subFps = 15, subBitrateKbps = 500,
                subRtspUrl = "rtsp://127.0.0.1:8554/live/sub")
        },
        credentials = { null }, cameraStatus = { status },
        cameraControl = { key, value ->
            commands += key to value
            if (accepted) {
                if (key == "fps") fps = value.toInt()
                if (key == "stream_bitrate_kbps") bitrate = value.toInt()
            }
            accepted
        },
        enableDiscovery = false
    )

    private fun call(s: OnvifServer, body: String, path: String = "/onvif/device_service") =
        s.handle("POST", path, emptyMap(),
            OnvifXml.envelope(body).toByteArray(Charsets.UTF_8))

    @Test fun absoluteFocusMoveAndAutoSettingsForwardToCameraService() {
        val s = server()
        val opts = call(s, "<timg:GetMoveOptions/>", "/onvif/imaging_service")
        assertEquals(200, opts.status)
        assertTrue(opts.text.contains("<tt:Absolute>"))
        assertEquals(200, call(s,
            "<timg:Move><timg:VideoSourceToken>vs0</timg:VideoSourceToken>" +
            "<timg:Focus><tt:Absolute><tt:Position>0.6</tt:Position></tt:Absolute></timg:Focus>" +
            "</timg:Move>", "/onvif/imaging_service").status)
        assertTrue(commands.contains("lg_main0_focus_mode" to "diopters"))
        assertTrue(commands.contains("lg_main0_focus_diopters" to "6.0"))

        assertEquals(200, call(s, "<timg:SetImagingSettings><timg:VideoSourceToken>vs0</timg:VideoSourceToken>" +
            "<timg:ImagingSettings><tt:Focus><tt:AutoFocusMode>AUTO</tt:AutoFocusMode></tt:Focus>" +
            "</timg:ImagingSettings></timg:SetImagingSettings>",
            "/onvif/imaging_service").status)
        assertTrue(commands.contains("lg_main0_focus_mode" to "auto"))
    }

    @Test fun invalidPositionAndUnsupportedControlDoNotClaimSuccess() {
        val s = server()
        val bad = call(s, "<timg:Move><tt:Absolute><tt:Position>2.5</tt:Position>" +
            "</tt:Absolute></timg:Move>", "/onvif/imaging_service")
        assertEquals(400, bad.status)
        assertTrue(bad.text.contains("Fault"))
        assertTrue(commands.isEmpty())
        val refused = server(accepted = false)
        val r = call(refused, "<timg:SetImagingSettings><tt:Focus>" +
            "<tt:AutoFocusMode>MANUAL</tt:AutoFocusMode></tt:Focus>" +
            "</timg:SetImagingSettings>", "/onvif/imaging_service")
        assertEquals(400, r.status)
    }

    @Test fun flashlightRelayMapsToActualTorchCommand() {
        val s = server()
        val list = call(s, "<tds:GetRelayOutputs/>")
        assertEquals(200, list.status)
        assertTrue(list.text.contains("torch0"))
        assertEquals(200, call(s,
            "<tds:SetRelayOutputState><tds:RelayOutputToken>torch0</tds:RelayOutputToken>" +
            "<tds:LogicalState>active</tds:LogicalState></tds:SetRelayOutputState>").status)
        assertTrue(commands.contains("torch" to "on"))
        val bad = call(s,
            "<tds:SetRelayOutputState><tds:RelayOutputToken>unknown</tds:RelayOutputToken>" +
            "<tds:LogicalState>active</tds:LogicalState></tds:SetRelayOutputState>")
        assertEquals(400, bad.status)
    }

    @Test fun media1AndMedia2ChangeActualSharedH264SettingsAndReturnSubUrl() {
        val s = server()
        val config = "<trt:Configuration token=\"vec_main\">" +
            "<tt:Encoding>H264</tt:Encoding>" +
            "<tt:Resolution><tt:Width>960</tt:Width><tt:Height>720</tt:Height></tt:Resolution>" +
            "<tt:Quality>4</tt:Quality><tt:RateControl>" +
            "<tt:FrameRateLimit>15</tt:FrameRateLimit><tt:BitrateLimit>12000</tt:BitrateLimit>" +
            "</tt:RateControl></trt:Configuration>"
        assertEquals(200, call(s,
            "<trt:SetVideoEncoderConfiguration>$config</trt:SetVideoEncoderConfiguration>",
            "/onvif/media_service").status)
        assertTrue(commands.contains("onvif_video_quality" to "4"))
        assertTrue(commands.contains("fps" to "15"))
        assertTrue(commands.contains("stream_bitrate_kbps" to "12000"))
        val readback = call(s, "<trt:GetVideoEncoderConfigurations/>", "/onvif/media_service")
        assertEquals(200, readback.status)
        assertTrue(readback.text.contains("<tt:BitrateLimit>12000</tt:BitrateLimit>"))
        assertTrue(readback.text.contains("<tt:FrameRateLimit>15</tt:FrameRateLimit>"))
        val sub = call(s, "<trt:GetStreamUri><trt:ProfileToken>sub</trt:ProfileToken></trt:GetStreamUri>",
            "/onvif/media_service")
        assertTrue(sub.text.contains("/live/sub"))
        assertFalse(sub.text.contains("rtsps://"))

        val m2 = call(s, "<tr2:SetVideoEncoderConfiguration>" +
            "<tr2:Configuration token=\"vec_main\"><tt:RateControl><tt:BitrateLimit>6000</tt:BitrateLimit>" +
            "</tt:RateControl></tr2:Configuration></tr2:SetVideoEncoderConfiguration>",
            "/onvif/media2_service")
        assertEquals(200, m2.status)
        assertTrue(commands.contains("stream_bitrate_kbps" to "6000"))
    }

    @Test fun mediaConfigurationRejectsUnsupportedResolutionsAndCodec() {
        val s = server()
        val bad = call(s, "<trt:SetVideoEncoderConfiguration>" +
            "<trt:Configuration token=\"vec_main\"><tt:Resolution>" +
            "<tt:Width>4160</tt:Width><tt:Height>3120</tt:Height></tt:Resolution>" +
            "</trt:Configuration></trt:SetVideoEncoderConfiguration>", "/onvif/media_service")
        assertEquals(400, bad.status)
        assertTrue(bad.text.contains("ActionNotSupported"))
        assertTrue(commands.isEmpty())
        val codec = call(s, "<trt:SetVideoEncoderConfiguration>" +
            "<trt:Configuration token=\"vec_main\"><tt:Encoding>H265</tt:Encoding>" +
            "</trt:Configuration></trt:SetVideoEncoderConfiguration>", "/onvif/media_service")
        assertEquals(400, codec.status)
    }

    @Test fun deviceHealthIsExplicitVendorTopicAndReportsLiveValues() {
        val s = server()
        val properties = call(s, "<tev:GetEventProperties/>", "/onvif/events_service")
        assertEquals(200, properties.status)
        for (tag in listOf("BatteryPercent", "BatteryTemperatureC", "SoCTemperatureC", "CpuPercent"))
            assertTrue("Vendor topic $tag missing", properties.text.contains("apph:$tag"))
        val sub = call(s, "<tev:CreatePullPointSubscription><tev:InitialTerminationTime>PT60S" +
            "</tev:InitialTerminationTime></tev:CreatePullPointSubscription>",
            "/onvif/events_service")
        assertEquals(200, sub.status)
        val address = OnvifXml.text(OnvifXml.parse(sub.text)?.documentElement, "Address")
        assertNotNull(address)
        val path = java.net.URI(address).path
        val poll = call(s, "<tev:PullMessages><tev:Timeout>PT0S</tev:Timeout>" +
            "<tev:MessageLimit>15</tev:MessageLimit></tev:PullMessages>", path)
        assertEquals(200, poll.status)
        assertTrue(poll.text.contains("DeviceHealth/BatteryPercent"))
        assertTrue(poll.text.contains("Value=\"78\""))
        assertTrue(poll.text.contains("BatteryTemperatureC"))
        assertTrue(poll.text.contains("SoCTemperatureC"))
    }
}
