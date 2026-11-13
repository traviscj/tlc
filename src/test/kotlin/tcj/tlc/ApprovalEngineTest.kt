package tcj.tlc

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ApprovalEngineTest {
  private fun g(vararg names: String) = names.map { RuleGroupDto(it, it.lowercase()) }
  private fun ev(req: Requirement, groups: List<RuleGroupDto>, approvals: Map<String, Set<String>>, min: Int? = null, by: String = "req") =
    ApprovalEngine.evaluate(req, min, groups, approvals, by)

  @Test
  fun `matching finds the assignment greedy misses`() {
    // Greedy gives A to ENG (first group), leaving nobody for RISK... unless B, who can only do ENG, displaces A.
    val m = ApprovalEngine.matching(listOf("ENG", "RISK"), mapOf("ENG" to listOf("a", "b"), "RISK" to listOf("a")))
    assertThat(m).isEqualTo(mapOf("ENG" to "b", "RISK" to "a"))
    // chain of three displacements
    val chain = ApprovalEngine.matching(listOf("G1", "G2", "G3"), mapOf("G1" to listOf("x", "y"), "G2" to listOf("x", "z"), "G3" to listOf("x")))
    assertThat(chain).isEqualTo(mapOf("G1" to "y", "G2" to "z", "G3" to "x"))
  }

  @Test
  fun `one person in every group is still one group`() {
    val e = ev(Requirement.MIN_GROUPS, g("A", "B", "C"), mapOf("solo" to setOf("A", "B", "C")), 2)
    assertThat(e.satisfied).isFalse()
    assertThat(e.have).isEqualTo(1)
  }

  @Test
  fun `majority thresholds`() {
    val two = mapOf("p" to setOf("A"), "q" to setOf("B"))
    assertThat(ev(Requirement.MAJORITY_OF_GROUPS, g("A", "B", "C"), two).satisfied).isTrue() // 2 of 3
    assertThat(ev(Requirement.MAJORITY_OF_GROUPS, g("A", "B", "C", "D"), two).satisfied).isFalse() // 2 of 4 is not a majority
    assertThat(ev(Requirement.MAJORITY_OF_GROUPS, g("A", "B", "C", "D"), two).needed).isEqualTo(3)
    assertThat(ev(Requirement.MAJORITY_OF_GROUPS, g("A"), mapOf("p" to setOf("A"))).satisfied).isTrue()
    assertThat(ev(Requirement.ALL_GROUPS, g("A", "B", "C"), two).satisfied).isFalse()
  }

  @Test
  fun `the requirement menu is every type unless the deployment narrows it`() {
    assertThat(Requirement.offered(null)).containsExactlyElementsOf(Requirement.entries)
    assertThat(Requirement.offered(listOf("all_groups", "ANY_NON_SELF_APPROVAL"))).containsExactly(Requirement.ALL_GROUPS, Requirement.ANY_NON_SELF_APPROVAL)
    org.assertj.core.api.Assertions.assertThatThrownBy { Requirement.offered(listOf("TWO_KEYS")) }.hasMessageContaining("bad requirement")
  }

  @Test
  fun `self approvals count only under ANY_APPROVAL`() {
    val self = mapOf("req" to setOf("A"))
    assertThat(ev(Requirement.ANY_APPROVAL, g(), self).satisfied).isTrue()
    assertThat(ev(Requirement.ANY_NON_SELF_APPROVAL, g(), self).satisfied).isFalse()
    assertThat(ev(Requirement.ALL_GROUPS, g("A"), self).satisfied).isFalse()
    assertThat(ev(Requirement.SLACK_NOTIFICATION, g("A"), mapOf()).satisfied).isTrue()
  }
}
