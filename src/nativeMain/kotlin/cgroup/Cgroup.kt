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
     * and apply the resource limits.
     *
     * When [pid] is non-null the process is also written into the leaf
     * cgroup.procs. Pass `null` to set up the directory and resources without
     * adding any process — the caller can use [addProcess] later.
     *
     * No-op when both [cgroupPath] and [resources] are null.
     */
    fun setup(
        pid: Int? = null,
        cgroupPath: String?,
        resources: LinuxResources?,
        deferPids: Boolean = false,
    )

    /**
     * Write [pid] (0 = the calling process) into cgroup.procs of the EXISTING
     * cgroup at [cgroupPath]. Unlike [setup], this neither creates the cgroup
     * nor touches controllers or limits — it only moves the process. Used by
     * exec to place the new process into the container's cgroup. Throws on
     * failure.
     */
    fun addProcess(
        pid: Int,
        cgroupPath: String,
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
