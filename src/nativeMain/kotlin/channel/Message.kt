package channel

import kotlinx.serialization.Serializable

/**
 * Messages exchanged between main and init processes
 *
 * There are only two processes that communicate:
 * - Main process (parent)
 * - Init process (PID 1 in container, Stage-2 from bootstrap.c)
 */
@Serializable
sealed class Message {
    @Serializable
    object InitReady : Message()

    @Serializable
    object WriteMapping : Message()

    @Serializable
    object MappingWritten : Message()

    @Serializable
    object SeccompNotify : Message()

    @Serializable
    object SeccompNotifyDone : Message()

    /**
     * Init → Main: request the main process to apply MOUNT_ATTR_IDMAP
     * on a mount tree fd sent via SCM_RIGHTS.
     *
     * @param uidMap kernel-format uid_map string ("containerID hostID size\n")
     * @param gidMap kernel-format gid_map string
     * @param recursive whether AT_RECURSIVE should be passed to mount_setattr
     * @param implied if true, main uses the container's own userns (no new userns created)
     */
    @Serializable
    data class MountFdRequest(
        val uidMap: String? = null,
        val gidMap: String? = null,
        val recursive: Boolean = false,
        val implied: Boolean = false,
    ) : Message()

    /** Main → Init: the mount_setattr has been applied to the tree fd. */
    @Serializable
    object MountFdDone : Message()

    @Serializable
    data class ExecFailed(
        val error: String,
    ) : Message()

    @Serializable
    data class OtherError(
        val error: String,
    ) : Message()
}
