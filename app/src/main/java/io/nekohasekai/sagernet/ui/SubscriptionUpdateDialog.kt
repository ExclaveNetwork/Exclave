package io.nekohasekai.sagernet.ui

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.databinding.LayoutSubscriptionUpdateBinding
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

object SubscriptionUpdateDialog {

    fun showManual(
        activity: ThemedActivity,
        groups: List<ProxyGroup>,
        onFinished: ((SubscriptionUpdater.UpdateSummary) -> Unit)? = null
    ): Job? {
        return show(
            activity,
            groups,
            forceUpdateWhenDisconnected = true,
            onFinished = onFinished
        )
    }

    fun show(
        activity: ThemedActivity,
        groups: List<ProxyGroup>,
        byUser: Boolean = false,
        forceUpdateWhenDisconnected: Boolean = false,
        onFinished: ((SubscriptionUpdater.UpdateSummary) -> Unit)? = null
    ): Job? {
        if (groups.isEmpty()) {
            activity.snackbar(R.string.group_not_a_subscription).show()
            return null
        }

        val binding = LayoutSubscriptionUpdateBinding.inflate(activity.layoutInflater)
        var job: Job? = null
        var completed = false
        var success = 0
        var errors = 0
        var skipped = 0
        var duplicates = 0
        var done = 0
        var currentGroupName = ""

        fun refresh() {
            binding.progress.max = groups.size
            binding.progress.progress = done
            val progressText = activity.getString(R.string.subscription_update_progress, done, groups.size)
            binding.progressText.text = if (currentGroupName.isNotEmpty() && done < groups.size) {
                "$progressText\n${activity.getString(R.string.subscription_update_message, currentGroupName)}"
            } else {
                progressText
            }
            binding.counters.text = activity.getString(R.string.subscription_update_counters, success, errors, skipped)
            binding.deduplication.text = activity.getString(R.string.subscription_dedup_progress, duplicates)
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.subscription_update)
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            refresh()
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
                job?.cancel()
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            if (!completed) job?.cancel()
        }
        dialog.show()

        job = runOnDefaultDispatcher {
            try {
                val summary = SubscriptionUpdater.updateGroups(
                    groups,
                    byUser = byUser,
                    forceUpdateWhenDisconnected = forceUpdateWhenDisconnected,
                    onGroupStart = { group ->
                        currentGroupName = group.displayName()
                        onMainDispatcher {
                            refresh()
                        }
                    },
                    onProgress = { summary ->
                        success = summary.success
                        errors = summary.errors
                        skipped = summary.skipped
                        duplicates = summary.duplicates
                        done = summary.done
                        onMainDispatcher {
                            refresh()
                        }
                    }
                )
                success = summary.success
                errors = summary.errors
                skipped = summary.skipped
                duplicates = summary.duplicates
                done = summary.done
                onFinished?.invoke(summary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += groups.size - done
                done = groups.size
                onFinished?.invoke(
                    SubscriptionUpdater.UpdateSummary(
                        total = groups.size,
                        done = done,
                        success = success,
                        errors = errors,
                        skipped = skipped,
                        duplicates = duplicates,
                        failedOrSkippedIds = groups.map { it.id }.toSet()
                    )
                )
                onMainDispatcher {
                    refresh()
                }
            }

            completed = true
            if (DataStore.subscriptionAutoSortGroups) {
                GroupManager.sortGroupsByTotalProfiles()
            }
            onMainDispatcher {
                dialog.dismiss()
                activity.snackbar(
                    activity.getString(
                        R.string.subscription_update_result,
                        success,
                        errors,
                        skipped,
                        duplicates
                    )
                ).show()
            }
        }

        return job
    }
}
