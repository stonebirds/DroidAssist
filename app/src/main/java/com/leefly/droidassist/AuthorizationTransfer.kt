package com.leefly.droidassist

import com.leefly.droidassist.protocol.CreateGrantResponse

data class AuthorizationCredentials(
    val matchingCode: String,
    val password: String,
)

fun formatAuthorizationText(serverUrl: String, grant: CreateGrantResponse): String = buildString {
    appendLine("DroidAssist 授权信息")
    appendLine("授权类型：${if (grant.expiresAtEpochMillis == null) "永久授权" else "临时授权"}")
    appendLine("服务器地址：$serverUrl")
    appendLine("匹配码：${grant.matchingCode}")
    append("连接密码：${grant.password}")
}

fun parseAuthorizationText(value: String): AuthorizationCredentials? {
    val matchingCode = Regex("匹配码\\s*[：:=]\\s*([0-9 -]+)")
        .find(value)
        ?.groupValues
        ?.get(1)
        ?.filter(Char::isDigit)
        ?.takeIf { it.length == 9 }
        ?: return null
    val password = Regex("连接密码\\s*[：:=]\\s*([A-Za-z0-9]+)")
        .find(value)
        ?.groupValues
        ?.get(1)
        ?.uppercase()
        ?.takeIf(String::isNotBlank)
        ?: return null
    return AuthorizationCredentials(matchingCode, password)
}
