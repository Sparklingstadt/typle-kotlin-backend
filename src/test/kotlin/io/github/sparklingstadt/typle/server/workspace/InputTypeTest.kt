package io.github.sparklingstadt.typle.server.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// The input type of a word (typle-r v0.108.0), as Go's input_type_test.go: optional, and when it is there one of
// command, code and word exactly.
class InputTypeTest {
  private fun list(word: String) = """[{"id":"a","name":"A","createdAt":"x","words":[$word],"records":[]}]"""

  @Test fun readsAndWritesTheInputType() {
    val w = decode(list("""{"display":"ls","input":"ls","annotation":"","input_type":"command"},{"display":"a","input":"a","annotation":""}"""), "[]")
    assertEquals(listOf("command", ""), w.lists[0].words.map { it.inputType })
    val encoded = encode(w.lists)
    assertTrue(""""annotation":"","input_type":"command"}""" in encoded && """{"display":"a","input":"a","annotation":""}""" in encoded, encoded)
    for (value in listOf("null", "\"\"", "\"Command\"", "\"other\"", "1")) {
      assertFailsWith<InvalidWorkspaceException>(value) {
        decode(list("""{"display":"a","input":"a","annotation":"","input_type":$value}"""), "[]")
      }
    }
    assertFailsWith<WorkspaceError> { checkLimits(listOf(w.lists[0].copy(words = listOf(Word("a", "a", "", inputType = "x"))))) }
  }

  // A pack file keeps the input type of its words, and refuses one that is not valid.
  @Test fun packFilesKeepTheInputType() {
    val w = decode("""[{"id":"a","name":"A","packId":"p","createdAt":"x","words":[{"display":"ls","input":"ls","annotation":"","input_type":"code"}],"records":[]}]""",
      "[]", """[{"id":"p","name":"P","createdAt":"x"}]""")
    val file = encodePackFile(w, "p")
    assertTrue("\"input_type\": \"code\"" in file, file)
    assertEquals(file, encodePackFileAgain(file))
    assertFailsWith<InvalidWorkspaceException> { encodePackFileAgain(file.replace("\"code\"", "\"\"")) }
  }

  // Since typle-r v2.1.0 the lists of a followed pack are saved without words, which the catalog puts back.
  @Test fun followedListsAreSavedWithoutWords() {
    val first = catalog[0]
    val packs = """[{"id":"f","name":"F","origin":{"catalogId":"${first.catalogId}","version":1,"followed":true},"createdAt":"x"}]"""
    val lists = """[{"id":"f1","name":"x","packId":"f","origin":{"listId":"${first.lists[0].id}"},"createdAt":"x","words":[],"records":[]}]"""
    val w = readSaved(lists, "[]", packs)
    assertEquals(first.lists[0].words, w.lists[0].words)
    assertEquals(listOf(emptyList<Word>()), withoutFollowedWords(w.packs, w.lists).map { it.words })
    // A list without an origin still needs its words.
    assertFailsWith<InvalidWorkspaceException> { decode(lists.replace(""""origin":{"listId":"${first.lists[0].id}"},""", ""), "[]", packs) }
  }
}
