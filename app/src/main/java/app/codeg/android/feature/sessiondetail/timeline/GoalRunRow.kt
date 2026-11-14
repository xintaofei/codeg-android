package app.codeg.android.feature.sessiondetail.timeline

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.codeg.android.feature.sessiondetail.rendering.GoalFrame
import app.codeg.android.feature.sessiondetail.rendering.summarizeActivity

/**
 * A Codex `/goal` run on the rail (web `GoalRunPart`): the goal's row ([GoalFrame]), opening onto
 * the run's own steps on a rail of their own — the same rows a reply draws.
 */
@Composable
fun GoalRunRow(content: NodeContent.GoalRun, modifier: Modifier = Modifier, disclosureKey: String? = null) {
    val run = content.run
    val stepCount = remember(run) { summarizeActivity(run.items).steps }
    GoalFrame(
        start = run.start,
        end = run.end,
        running = run.isRunning,
        stepCount = stepCount,
        modifier = modifier,
        disclosureKey = disclosureKey,
        steps = if (content.steps.isEmpty()) null else {
            {
                Column(Modifier.fillMaxWidth()) {
                    for (node in content.steps) {
                        // By id, so a step keeps its state (an opened card) while the run grows.
                        key(node.id) {
                            TimelineRow(
                                marker = node.marker,
                                connectTop = node.connectTop,
                                connectBottom = node.connectBottom,
                                startsGroup = false,
                                rail = node.rail,
                            ) {
                                NodeBody(node)
                            }
                        }
                    }
                }
            }
        },
    )
}
