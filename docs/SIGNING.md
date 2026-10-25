# Signature des releases

GitHub Actions doit signer les APK avec **la meme cle** que l'application
installee, sinon Android refuse la mise a jour (signature differente).

Secrets a creer dans `Settings > Secrets and variables > Actions` :

- `KEYSTORE_BASE64` : keystore encode en base64
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Pour conserver les mises a jour de l'installation actuelle (APK debug), on peut
utiliser le keystore debug local :

```bash
base64 -w0 ~/.android/debug.keystore
# KEY_ALIAS=androiddebugkey
# KEYSTORE_PASSWORD=android
# KEY_PASSWORD=android
```

Pour une cle de production :

```bash
keytool -genkeypair -v -keystore release.keystore -alias ytmp3 \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.keystore
```

Dans ce cas, la premiere installation signee avec cette cle necessitera de
desinstaller l'ancienne APK (les donnees de l'appli seront perdues). Ne jamais
committer le keystore ni ses mots de passe.

## Releases

- tag `v*` pousse -> workflow `Release on tag` -> APK + release GitHub.
- tous les jours a 03:23 UTC -> `Daily extractor check` : si NewPipe Extractor
  a une nouvelle version, bump du plugin, tag `v<base>.<run>` et nouvelle release.
