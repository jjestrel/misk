package org.assertj.core.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

internal class AssertExtensionsTest {
  @Test
  fun assertAsJsonMatches() {
    // Should ignore all of the whitespace differences here
    assertThat("""
{"my_structure"    :   [  "this", 45,   "zip" ],
   "my_value":"another value"


}
""").isEqualToAsJson("""
{
  "my_structure" : ["this", 45, "zip"],
  "my_value"     : "another value"
}
""")
  }

  @Test
  fun assertAsJsonMismatchedFieldName() {
    assertThat(assertFailsWith<AssertionError> {
      assertThat("""
{
  "my_structure2"   : ["this", 45, "zip" ],
  "my_value"        : "another value"
}
""").isEqualToAsJson("""
{
  "my_structure" : ["this", 45, "zip"],
  "my_value"     : "another value"
}
""")
    }).hasMessage(
        """expected:<"{ "my_structure[]" : [ "this" , 45, "...> but was:<"{ "my_structure[2]" : [ "this" , 45, "...>""")
  }

  @Test
  fun assertAsJsonMismatchedFieldValue() {
    assertThat(assertFailsWith<AssertionError> {
      assertThat("""
{
  "my_structure"    : ["thisisit", 45, "zip" ],
  "my_value"        : "another value"
}
""").isEqualToAsJson("""
{
  "my_structure" : ["this", 45, "zip"],
  "my_value"     : "another value"
}
""")
    }).hasMessage(
        """expected:<...structure" : [ "this[]" , 45, "zip" ], "my...> but was:<...structure" : [ "this[isit]" , 45, "zip" ], "my...>""")
  }

  @Test
  fun assertAsJsonMismatchedWhitespaceInStringFieldValue() {
    assertThat(assertFailsWith<AssertionError> {
      assertThat("""
{
  "my_structure"    : ["this", 45, "zip" ],
  "my_value"        : "another value"
}
""").isEqualToAsJson("""
{
  "my_structure" : ["thi  s", 45, "zip"],
  "my_value"     : "another value"
}
""")
    }).hasMessage(
        """expected:<..._structure" : [ "thi[  ]s" , 45, "zip" ], "m...> but was:<..._structure" : [ "thi[]s" , 45, "zip" ], "m...>""")
  }
}
