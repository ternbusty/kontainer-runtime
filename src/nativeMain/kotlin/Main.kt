import kotlinx.cinterop.*
import platform.linux.__NR_unshare
import platform.posix.*
import spec.*
import namespace.*
import rootfs.*

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>): Unit = memScoped {
    // Load OCI spec from bundle
    val bundlePath = if (args.isNotEmpty()) args[0] else "."
    val configPath = "$bundlePath/config.json"

    fprintf(stderr, "Loading spec from %s\n", configPath)
    val spec = try {
        loadSpec(configPath)
    } catch (e: Exception) {
        fprintf(stderr, "Failed to load spec: %s\n", e.message ?: "unknown error")
        exit(1)
        return
    }

    fprintf(stderr, "Loaded spec version %s\n", spec.ociVersion)

    // Get rootfs absolute path
    val rootfsPath = if (spec.root?.path?.startsWith("/") == true) {
        spec.root.path
    } else {
        "$bundlePath/${spec.root?.path ?: "rootfs"}"
    }

    fprintf(stderr, "Rootfs path: %s\n", rootfsPath)
    fprintf(stderr, "parent getpid=%d getppid=%d\n", getpid(), getppid())

    val sv = IntArray(2)
    if (socketpair(AF_UNIX, SOCK_SEQPACKET, 0, sv.refTo(0)) != 0) {
        perror("socketpair")
        exit(1)
    }

    val pid = fork()
    when (pid) {
        -1 -> {
            perror("fork")
            exit(1)
        }

        0 -> { // 中間プロセス
            close(sv[0])

            // User namespace をまず分離
            if (hasNamespace(spec.linux?.namespaces, "user")) {
                if (syscall(__NR_unshare.toLong(), CLONE_NEWUSER) == -1L) {
                    perror("unshare(CLONE_NEWUSER)")
                    _exit(1)
                }
                fprintf(stderr, "intermediate: unshared user namespace\n")
            }

            fprintf(stderr, "intermediate getpid=%d getppid=%d\n", getpid(), getppid())

            // 親へマッピング要求を送る
            val req = "MAP ${getpid()}\n"
            fprintf(stderr, "intermediate: requesting mapping pid=%d\n", getpid())
            if (send(sv[1], req.cstr.ptr, req.length.toULong(), 0) == -1L) {
                perror("send(map request)")
                _exit(1)
            }

            // 親からの完了応答を待つ
            val ackBuf = allocArray<ByteVar>(64)
            val rn = recv(sv[1], ackBuf, 63.toULong(), 0)
            if (rn == -1L) {
                perror("recv(ack)")
                _exit(1)
            } else {
                ackBuf[rn.toInt()] = 0
                val ack = ackBuf.toKString()
                if (!ack.startsWith("MAPPED")) {
                    fprintf(stderr, "intermediate: mapping failed ack=%s\n", ack)
                    _exit(1)
                }
            }

            // UID/GID を 0 に設定（user namespace 内のroot）
            if (setuid(0u) != 0 || setgid(0u) != 0) {
                perror("setuid/setgid")
                _exit(1)
            }

            // 追加のnamespaceを分離（user, pid以外）
            spec.linux?.namespaces?.let { namespaces ->
                try {
                    // Mount, Network, UTS, IPC namespace をunshare
                    for (ns in namespaces) {
                        when (ns.type) {
                            "mount", "network", "uts", "ipc" -> {
                                val flag = when (ns.type) {
                                    "mount" -> CLONE_NEWNS
                                    "network" -> CLONE_NEWNET
                                    "uts" -> CLONE_NEWUTS
                                    "ipc" -> CLONE_NEWIPC
                                    else -> continue
                                }
                                if (syscall(__NR_unshare.toLong(), flag) == -1L) {
                                    perror("unshare(${ns.type})")
                                    _exit(1)
                                }
                                fprintf(stderr, "intermediate: unshared %s namespace\n", ns.type)
                            }
                        }
                    }
                } catch (e: Exception) {
                    fprintf(stderr, "intermediate: failed to unshare namespaces: %s\n", e.message ?: "unknown")
                    _exit(1)
                }
            }

            // PID namespace を分離
            if (hasNamespace(spec.linux?.namespaces, "pid")) {
                if (syscall(__NR_unshare.toLong(), CLONE_NEWPID) == -1L) {
                    perror("unshare(CLONE_NEWPID)")
                    _exit(1)
                }
                fprintf(stderr, "intermediate: unshared pid namespace\n")
            }

            // 兄弟プロセスとして init を生成
            val cpid = fork()
            if (cpid == -1) {
                perror("fork(init)")
                _exit(1)
            }
            if (cpid == 0) {
                // ここが新しい PID namespace の init 相当
                fprintf(stderr, "init getpid=%d getppid=%d\n", getpid(), getppid())

                try {
                    // Hostname を設定（UTS namespace内）
                    spec.hostname?.let { hostname ->
                        if (sethostname(hostname, hostname.length.toULong()) != 0) {
                            perror("sethostname")
                            fprintf(stderr, "Warning: failed to set hostname\n")
                        } else {
                            fprintf(stderr, "Set hostname to %s\n", hostname)
                        }
                    }

                    // Rootfs を準備
                    if (hasNamespace(spec.linux?.namespaces, "mount")) {
                        prepareRootfs(rootfsPath)
                        pivotRoot(rootfsPath)
                    } else {
                        fprintf(stderr, "No mount namespace, skipping rootfs preparation\n")
                    }

                    // 作業ディレクトリへ移動
                    val cwd = spec.process?.cwd ?: "/"
                    if (chdir(cwd) != 0) {
                        perror("chdir")
                        fprintf(stderr, "Warning: failed to chdir to %s\n", cwd)
                    } else {
                        fprintf(stderr, "Changed directory to %s\n", cwd)
                    }

                    // コンテナ内での動作確認
                    fprintf(stderr, "=== Container is ready ===\n")
                    fprintf(stderr, "PID: %d\n", getpid())
                    fprintf(stderr, "CWD: %s\n", cwd)

                    // 実際のプロセス実行の代わりに、今はsleepで確認
                    fprintf(stderr, "Sleeping for 30 seconds...\n")
                    sleep(30u)

                    fprintf(stderr, "Container exiting\n")
                } catch (e: Exception) {
                    fprintf(stderr, "init: error: %s\n", e.message ?: "unknown")
                    _exit(1)
                }

                _exit(0)
            } else {
                // 中間プロセスは init の終了を待つ
                val st = alloc<IntVar>()
                if (waitpid(cpid, st.ptr, 0) == -1) {
                    perror("waitpid(init)")
                    _exit(1)
                }
                _exit(0)
            }
        }

        else -> { // 親プロセス
            close(sv[1])

            // 中間プロセスからの MAP 要求を受信
            val buf = allocArray<ByteVar>(128)
            val n = recv(sv[0], buf, 127.toULong(), 0)
            if (n == -1L) {
                perror("recv(map request)")
                close(sv[0])
                exit(1)
            }
            buf[n.toInt()] = 0
            val s = buf.toKString().trim()

            // 形式 MAP <pid>
            val parts = s.split(" ")
            if (parts.size != 2 || parts[0] != "MAP") {
                fprintf(stderr, "parent: bad request: %s\n", s)
                close(sv[0])
                exit(1)
            }
            val targetPid = parts[1].toInt()
            fprintf(stderr, "parent: mapping for pid=%d\n", targetPid)

            // 実効 UID/GID を 0..0 にマップする例
            val hostUid = geteuid().toUInt()
            val hostGid = getegid().toUInt()

            // setgroups を禁止してから gid_map を書くのがカーネルの制約
            if (!writeText("/proc/$targetPid/setgroups", "deny\n")) {
                perror("write setgroups")
                close(sv[0])
                exit(1)
            }
            // 単一の ID 範囲をマップ
            val uidMap = "${0} ${hostUid} ${1}\n"
            val gidMap = "${0} ${hostGid} ${1}\n"

            if (!writeText("/proc/$targetPid/uid_map", uidMap)) {
                fprintf(stderr, "parent: failed uid_map host=%u\n", hostUid)
                close(sv[0]); exit(1)
            }
            if (!writeText("/proc/$targetPid/gid_map", gidMap)) {
                fprintf(stderr, "parent: failed gid_map host=%u\n", hostGid)
                close(sv[0]); exit(1)
            }

            // マッピング完了を応答
            val ack = "MAPPED\n"
            if (send(sv[0], ack.cstr.ptr, ack.length.toULong(), 0) == -1L) {
                perror("send(ack)")
                close(sv[0]); exit(1)
            }

            // 中間プロセスの終了待ち
            val st = alloc<IntVar>()
            if (waitpid(pid, st.ptr, 0) == -1) {
                perror("waitpid(intermediate)")
                close(sv[0]); exit(1)
            }

            close(sv[0])
            fprintf(stderr, "parent: done\n")
        }
    }
}

// 簡易ファイル書き込みユーティリティ
@OptIn(ExperimentalForeignApi::class)
private fun writeText(path: String, content: String): Boolean {
    val fp = fopen(path, "w")
    if (fp == null) return false
    memScoped {
        val cs = content.cstr
        if (fwrite(cs.ptr, 1.convert(), content.length.convert(), fp) < content.length.convert()) {
            fclose(fp)
            return false
        }
    }
    return fclose(fp) == 0
}
