#ifndef KONTAINER_BOOTSTRAP_H
#define KONTAINER_BOOTSTRAP_H

#include <sys/types.h>

/**
 * Check if the current process is the init process
 * Returns 1 if init process, 0 otherwise
 */
int kontainer_is_init_process(void);

/**
 * Get the init process PID (set by bootstrap)
 * Returns the PID or -1 if not available
 */
int kontainer_get_init_pid(void);

/**
 * fork()-like clone3(2) with CLONE_INTO_CGROUP: the child starts its life
 * inside the cgroup referred to by the directory fd cgroup_fd, so no later
 * cgroup.procs write (and the multi-millisecond task migration it costs)
 * is needed. Semantics match fork(): returns the child's PID in the parent,
 * 0 in the child, -1 with errno set on failure (e.g. ENOSYS/EINVAL on kernels
 * before 5.7, EACCES/EBUSY if the cgroup cannot be entered) — callers then
 * fall back to fork() + cgroup.procs.
 *
 * Like a raw clone the child does not run pthread_atfork handlers, so the
 * caller must only perform async-signal-safe work (close/dup2/execve) in
 * the child.
 */
pid_t kontainer_clone_into_cgroup(int cgroup_fd);

/**
 * The process environment (extern char **environ), for callers that build an
 * execve envp array before spawning a child.
 */
char **kontainer_environ(void);

/**
 * execve(2) with pre-built argv/envp, then perror + _exit(1) on failure.
 * Takes the path as a raw pointer (not a Kotlin String) so the call performs
 * no allocation — it is meant for the child side of clone3/fork, where only
 * async-signal-safe work is allowed. Never returns.
 */
void kontainer_execve(const void *path, char *const argv[], char *const envp[]);

#endif // KONTAINER_BOOTSTRAP_H
