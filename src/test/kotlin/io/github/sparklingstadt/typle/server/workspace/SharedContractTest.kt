package io.github.sparklingstadt.typle.server.workspace

import io.github.sparklingstadt.typle.server.json.JsonNumber
import io.github.sparklingstadt.typle.server.json.JsonObject
import io.github.sparklingstadt.typle.server.json.JsonValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue

class SharedContractTest {
  @Test fun followsTheSameWorkspaceContractAsGoAndAndroid() {
    val root = System.getenv("TYPLE_R")
    assumeTrue(!root.isNullOrEmpty(), "set TYPLE_R to run the shared contract")
    val fixture = JsonValue.parse(File(root!!, "tests/contracts/workspace.json").readText()) as JsonObject
    assertEquals(JsonNumber("1"), fixture.members["version"])
    val limits = fixture.members.getValue("limits") as JsonObject
    for ((name, value) in mapOf("lists" to MAX_LISTS, "category" to MAX_CATEGORY, "tag" to MAX_TAG, "tags" to MAX_TAGS)) {
      assertEquals(JsonNumber(value.toString()), limits.members[name], name)
    }
    val saved = fixture.members.getValue("saved") as JsonObject
    val lists = saved.members.getValue("lists")
    val devices = saved.members.getValue("devices")
    val document = decode(lists.toJson(), devices.toJson())
    checkLimits(document.lists)
    assertEquals(lists, JsonValue.parse(encode(document.lists)))
    assertEquals(devices, JsonValue.parse(encodeDevices(document.devices)))
  }
}
