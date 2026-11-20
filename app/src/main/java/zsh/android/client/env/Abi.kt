package zsh.android.client.env

/**
 * 设备 ABI 归一化：把 Android ABI 映射到 Termux / Ubuntu / Node 各自的命名。
 * 与 DSHA 只支持 arm64 不同，ZCodeA 同时支持 arm64 与 x86_64（后者便于模拟器开发）。
 */
enum class Abi(val androidAbi: String, val termuxArch: String, val ubuntuArch: String, val nodeArch: String) {
    ARM64("arm64-v8a", "aarch64", "arm64", "arm64"),
    X64("x86_64", "x86_64", "amd64", "x64");

    companion object {
        fun detect(supportedAbis: Array<String>): Abi? =
            supportedAbis.firstOrNull()?.let { fromAndroid(it) }

        fun fromAndroid(abi: String): Abi? = entries.firstOrNull { it.androidAbi == abi }
    }
}
