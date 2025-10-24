#ifndef KONTAINER_BOOTSTRAP_H
#define KONTAINER_BOOTSTRAP_H

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

#endif // KONTAINER_BOOTSTRAP_H
