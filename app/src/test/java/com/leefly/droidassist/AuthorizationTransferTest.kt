package com.leefly.droidassist

import com.leefly.droidassist.protocol.CreateGrantResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthorizationTransferTest {
    @Test
    fun formattedAuthorizationCanBeParsed() {
        val text = formatAuthorizationText(
            "https://example.trycloudflare.com",
            CreateGrantResponse("grant-id", "123456789", "ABC234XY", 1_000L),
        )

        assertEquals(AuthorizationCredentials("123456789", "ABC234XY"), parseAuthorizationText(text))
    }

    @Test
    fun invalidClipboardTextIsRejected() {
        assertNull(parseAuthorizationText("普通剪贴板内容"))
        assertNull(parseAuthorizationText("匹配码：123\n连接密码：ABC234XY"))
    }
}
