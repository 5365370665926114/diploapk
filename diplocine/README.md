# DiploCine Android

Abra esta pasta no Android Studio ou compile com JDK 17, Android SDK 35 e Gradle 8.9.

```sh
gradle -p diplocine :app:assembleDebug
```

O APK de testes fica em `diplocine/app/build/outputs/apk/debug/app-debug.apk`. O fluxo DiploCine APK do GitHub Actions também pode gerar o pacote. Defina `DIPLOCINE_SITE_URL` com a origem HTTPS do deploy Vercel para preencher o endereço no build. Sem essa variável, o app solicita o endereço e verifica a API.

O mesmo pacote tem entrada no launcher Android e Android TV. O app salva o modo do dispositivo e usa a API da versão Vercel/Supabase de `diplooo`. As alterações de validade de código e sessão da TV estão na branch `feature/android-tv-pairing` desse repositório. Aplique a migração SQL antes de implantar a API.

O player Media3 é provisório até a chegada do player do DiploCine. Ele usa fontes HTTPS retornadas por `/api/player/source`, grava a posição no aparelho e envia eventos de reprodução à API. Minha Lista, a cor e a retomada usam o estado compartilhado do perfil entre TV e celular. A interface web atual ainda precisa ler os campos do app para exibi-los. Canais diretos vêm de `/channels.json`. O funcionamento depende das fontes configuradas no site. Os links externos de canais são abertos no navegador.

Teste em celular Android e Android TV com o mesmo APK. Confira instalação, escolha do modo, QR e entrada manual, expiração, revogação, PIN, cor por perfil, foco usando somente o controle, reprodução, episódio seguinte e retorno do modo tela cheia.
