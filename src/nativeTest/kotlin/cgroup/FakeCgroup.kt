package cgroup

import spec.LinuxResources

/**
 * In-memory [Cgroup] for tests of higher layers (Create / Delete / Ps).
 *
 * Records every call as a string. [getPids] returns whatever a test
 * preseeds via [pidsByPath]; missing paths return an empty list.
 */
class FakeCgroup : Cgroup {
    val calls: MutableList<String> = mutableListOf()
    val pidsByPath: MutableMap<String, List<Int>> = mutableMapOf()

    override fun setup(
        pid: Int,
        cgroupPath: String?,
        resources: LinuxResources?,
    ) {
        calls += "setup(pid=$pid, cgroupPath=$cgroupPath, hasResources=${resources != null})"
    }

    override fun cleanup(cgroupPath: String?) {
        calls += "cleanup(cgroupPath=$cgroupPath)"
    }

    override fun getPids(cgroupPath: String): List<Int> {
        calls += "getPids(cgroupPath=$cgroupPath)"
        return pidsByPath[cgroupPath] ?: emptyList()
    }
}
