package com.firebolt9907.tangentbrowser
import com.firebolt9907.tangentbrowser.ui.content.LoginFieldProbe
import java.io.File
import org.junit.Test
class ScratchDump {
    @Test fun dump() {
        mapOf(
            "detect" to LoginFieldProbe.buildDetectScript(),
            "fillpw" to LoginFieldProbe.buildFillScript("me@purdue.edu","s3cret"),
            "filluser" to LoginFieldProbe.buildFillScript("me@purdue.edu","s3cret", LoginFieldProbe.Stage.User),
            "submitpw" to LoginFieldProbe.buildSubmitScript(),
            "submituser" to LoginFieldProbe.buildSubmitScript(LoginFieldProbe.Stage.User),
        ).forEach { (n, js) ->
            File("/tmp/probejs-$n.js").writeText(js.trimStart().removePrefix("javascript:"))
        }
    }
}
