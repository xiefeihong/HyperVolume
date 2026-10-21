package com.xiefeihong.hypervolume.core

/**
 * root shell 命令执行结果（退出码 + 合并输出）。不可变值对象。
 *
 * 仅由 [Shell] 构造，调用方通过 [isSuccess] 判断 `su` 退出码是否为 0。
 */
class ShellResult(val code: Int, val output: String) {

    val isSuccess: Boolean get() = code == 0
}
