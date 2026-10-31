# Android 正式版签名

正式 APK 必须使用长期保管的签名密钥。若要覆盖升级已安装的正式版，必须使用与它相同的签名密钥；新生成的密钥无法覆盖其他签名的同包名应用。本项目 debug 包使用 `.debug` 包名，可以与正式包并存。删除应用前请先备份所需数据。请勿把密钥、密码或 Base64 内容提交到仓库或发送到聊天。

## 创建密钥

如已有正式签名密钥，请直接使用已有密钥，不要另建。否则，在可信设备的终端中执行（需要 JDK `keytool`）：

```sh
umask 077
mkdir -p "$HOME/.local/share/rustclipsync-signing"
chmod 700 "$HOME/.local/share/rustclipsync-signing"
test ! -e "$HOME/.local/share/rustclipsync-signing/release.p12" || { echo '密钥文件已存在，请勿覆盖'; exit 1; }
keytool -genkeypair -keystore "$HOME/.local/share/rustclipsync-signing/release.p12" \
  -storetype PKCS12 -alias rustclipsync -keyalg RSA -keysize 3072 \
  -validity 10000 -dname 'CN=RustClipSync'
```

按提示输入并确认强密码；此 PKCS12 示例的密钥密码与密钥库密码相同。请离线备份 `release.p12`、密码和别名：丢失签名密钥通常意味着无法继续为同一应用签名更新。

## 配置 GitHub Actions

在 [Android 仓库 Actions 密钥设置](https://github.com/Infinifar/fcitx5-android-bibi-keyboard/settings/secrets/actions)中添加以下四个 **repository secrets**：

| 名称 | 值 |
| --- | --- |
| `KEYSTORE_BASE64` | 密钥库文件的 Base64 编码，不是文件路径 |
| `KEYSTORE_PASSWORD` | 密钥库密码 |
| `KEY_ALIAS` | 密钥别名，例如 `rustclipsync` |
| `KEY_PASSWORD` | 密钥密码；按上例生成时与密钥库密码相同 |

也可以在已通过 `gh auth login` 登录的可信终端中设置，Base64 不会打印到屏幕：

```sh
repo='Infinifar/fcitx5-android-bibi-keyboard'
gh secret set KEYSTORE_BASE64 --repo "$repo" < <(base64 -w0 "$HOME/.local/share/rustclipsync-signing/release.p12")
gh secret set KEY_ALIAS --repo "$repo" --body rustclipsync
read -rs -p '密钥库密码：' rcs_sign_password; printf '\n'
printf '%s' "$rcs_sign_password" | gh secret set KEYSTORE_PASSWORD --repo "$repo"
printf '%s' "$rcs_sign_password" | gh secret set KEY_PASSWORD --repo "$repo"
unset rcs_sign_password
```

完成后在仓库 Actions 中运行 **Build signed ARM64 release APK**，选择 `master`。工作流构建 release、校验签名及架构，并将 APK 和 SHA-256 文件作为构建产物保留 30 天；它不会自动创建 GitHub Release 或发布到应用商店。产物可供下载，但正式分发前仍需真实设备验收。

参考：[Android 签名文档](https://developer.android.com/studio/publish/app-signing)、[GitHub Actions secrets 文档](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)。
