# Сборка и инструменты разработчика

Как собрать «Значки Flyme» и чем пользоваться при разработке. Обзор проекта — в
[README](../README.md), устройство сборки и самого приложения — в [how-it-works.md](how-it-works.md).

Готового APK нет: приложение собирается поверх UI-библиотеки Flyme 12 из системного Калькулятора
вашего телефона, поэтому собранный APK только для личного использования.

## Что нужно

- JDK 17+ (`java`, `javac`, `keytool` в `PATH`);
- Python 3, только стандартная библиотека (для `tools/devtool.py dump` ещё Pillow);
- Android build-tools 36: aapt2, d8, zipalign, apksigner, dexdump, `core-lambda-stubs.jar`;
- `android.jar` платформы 36;
- apktool 3.x (`apktool.jar`);
- adb и телефон с Flyme 12.6 по adb: с него берётся Калькулятор и на него ставится сборка.

## Настройка

Скопируйте `build.local.example.json` в `build.local.json` и пропишите свои значения. Файл не попадает
в git. Любой ключ можно задать и переменной окружения `FI_<КЛЮЧ>`, она главнее файла. Относительные
пути считаются от папки со скриптом.

| Ключ | Переменная | По умолчанию | Что это |
|---|---|---|---|
| `build_tools` | `FI_BUILD_TOOLS` | `$ANDROID_HOME/build-tools/36.0.0` | aapt2, d8, zipalign, apksigner, dexdump |
| `android_jar` | `FI_ANDROID_JAR` | `$ANDROID_HOME/platforms/android-36/android.jar` | платформа 36 |
| `apktool` | `FI_APKTOOL` | — (обязательно) | `apktool.jar` 3.x |
| `keys` | `FI_KEYS` | `keys/` | папка ключа подписи |
| `base_apk` | `FI_BASE_APK` | `build/FlymeCalculator.apk` | Калькулятор 12.6; нет файла — заберётся с телефона |
| `work` | `FI_WORK` | `build/` | папка сборки |
| `adb` | `FI_ADB` | `adb` из `PATH` | |
| `device` | `FI_DEVICE` | пусто — единственное подключённое | адрес телефона для adb, например `192.168.0.10:5555` |
| `key_dname` | `FI_KEY_DNAME` | `CN=Flyme Icons` | владелец нового сертификата подписи |

`ANDROID_HOME` или `ANDROID_SDK_ROOT` указывает на Android SDK; без него `build_tools` и `android_jar`
нужно задать явно.

## Команды

```
python -I build.py            # APK в build/flymeicons.apk
python -I build.py --install  # и сразу поставить на телефон
```

`--install` кладёт APK на телефон и ставит через `pm install -r`. Телефон должен быть разблокирован,
иначе будет `INSTALL_FAILED_USER_RESTRICTED`.

## Версия

Имя версии ставится при каждой сборке: `Release[ГГГГММДД-ЧЧММ]` по времени сборки. Двоеточия в нём нет
намеренно: apktool и aapt2 обрезают имя версии на нём. Номер версии `VERSION_CODE` в `build.py` меняется
вручную, когда сборка должна считаться обновлением.

## Базовый APK

Если файла из `base_apk` нет, сборка забирает системный Калькулятор с телефона
(`/system/MzApp/FlymeCalculator`) и сверяет его sha256 с проверенной сборкой 12.0.17. Другая сборка
не запрещена, но имена из `java/stubs` относятся именно к этой: если они не совпадут, сборку остановит
проверка ссылок. Распакованная база лежит в `<work>/base` и пересобирается сама, когда меняется
базовый APK.

## Ключ подписи

Создаётся при первой сборке в `keys/` (или в `FI_KEYS`): `flyme-icons.jks` и пароль к нему в
`flyme-icons.pass`. Пароль передаётся keytool и apksigner через переменную окружения и файл, а не в
командной строке. Оба файла не попадают в git.

Сохраните их отдельно. Без этого ключа уже установленное приложение не обновить, придется удалить его
вместе с настройками.

## Инструменты разработчика

`tools/devtool.py` берёт те же настройки (adb, устройство, папку сборки) и складывает результаты в
`<work>/dev`:

```
python -I tools/devtool.py shot <имя>     # скриншот телефона
python -I tools/devtool.py dump           # превью «сейчас / день / ночь» всех значков + листы (нужен Pillow)
python -I tools/devtool.py pack [--night] # собрать набор ADW на телефоне, проверить подпись и выравнивание
python -I tools/devtool.py logs           # строки журнала приложения
```

`dump` и `pack` запускают `DevActivity`. Она закрыта разрешением `android.permission.DUMP`: оно есть
у adb shell, но не у обычных приложений.

## Скриншоты для README

Лежат в `docs/screenshots`: 540 × 1260 px, тёмная тема, английский интерфейс. Снимок телефона
(1368 × 3192) уменьшается Pillow (`Image.resize`, `LANCZOS`), PNG сохраняется с `optimize=True`.

Язык интерфейса включается только для этого приложения, язык системы не меняется:

```
adb shell cmd locale set-app-locales ru.neoncatrc.flymeicons --locales en-US
adb shell cmd locale set-app-locales ru.neoncatrc.flymeicons    # вернуть как было
```

Перед кадром дождитесь, пока загрузится список приложений: сразу после запуска экран пустой.
