package com.kaelixbox.container

/**
 * 默认容器镜像下载配置。
 *
 * 镜像来自外部官方仓库 Cateners/tiny_container，本仓库只存 Android 源码，
 * 不托管镜像文件。下载时先尝试 aka.ms 加速短链，失败后回退原始 GitHub 直链。
 */
object ImageConfig {

    const val IMAGE_FILENAME = "debian-xfce.tar.xz"

    /** aka.ms 加速短链（指向下方 GITHUB_RELEASE_URL）。需自行配置短链跳转。 */
    const val AKAMS_MIRROR_URL = "https://aka.ms/kaelix-debian13"

    /** 原始 GitHub Release 直链（备选回退）。 */
    const val GITHUB_RELEASE_URL =
        "https://github.com/Cateners/tiny_container/releases/download/v1.1.0/$IMAGE_FILENAME"

    /**
     * 预期 SHA256 校验值。留空则跳过校验。
     * 官方 Release 未发布该文件的 sha256sum，默认留空跳过；
     * 如需启用，请手动填入 debian-xfce.tar.xz 的 sha256sum。
     */
    const val EXPECTED_SHA256 = "1da3c9cca2d7c0d69e965be707971db080fea1bcf2217005534fee56a4a669b0"

    /**
     * 返回生效的加速镜像地址：用户自定义 > aka.ms 默认。
     */
    fun mirrorUrl(customMirror: String): String =
        if (customMirror.isNotBlank()) customMirror else AKAMS_MIRROR_URL
}
