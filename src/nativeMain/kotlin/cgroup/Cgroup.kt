package cgroup

import spec.LinuxResources

/**
 * Abstraction over cgroup operations the runtime invokes.
 *
 * Domain code (process / command) calls methods on a [Cgroup] instance
 * rather than touching cgroupfs directly. The production implementation
 * is [CgroupV2]; tests inject a fake.
 */
interface Cgroup {
    /**
     * Create a cgroup at [cgroupPath] (relative to the cgroup root), enable the
     * controllers required by [resources] in every ancestor's cgroup.subtree_control,
     * place [pid] in the leaf cgroup.procs, and apply the resource limits.
     *
     * No-op when both [cgroupPath] and [resources] are null.
     */
    fun setup(
        pid: Int,
        cgroupPath: String?,
        resources: LinuxResources?,
    )

    /**
     * Best-effort removal of the cgroup directory at [cgroupPath]. Logs a warning
     * on failure (e.g. cgroup not empty) and never throws.
     */
    fun cleanup(cgroupPath: String?)

    /**
     * Read the PIDs in the cgroup at [cgroupPath] from cgroup.procs.
     */
    fun getPids(cgroupPath: String): List<Int>
}
