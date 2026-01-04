# MashIA (Android)

Proyecto Android inicial para la app MashIA. Repositorio local configurado en la rama principal `main`.

## Requisitos

- Android Studio (Koala o superior), JDK 17
- Gradle Wrapper se generará al abrir el proyecto en Android Studio (o ejecutando `gradle wrapper`).

## Estructura

- `app/`: módulo de aplicación Android (Kotlin + AndroidX)
- `build.gradle.kts` y `settings.gradle.kts`: configuración de Gradle

## Ejecutar

1. Abre la carpeta del repo en Android Studio.
2. Deja que sincronice Gradle y descargue dependencias.
3. Ejecuta la app en un emulador o dispositivo.

## Login con Google (Firebase)

Para que el botón "Continue with Google" funcione con Firebase Auth:

- Crea un proyecto en Firebase Console y añade una app Android con el package `com.rophiroth.mashia`.
- Ejecuta `./gradlew signingReport` y copia el `SHA1` de la variante `debug` (y `release` si corresponde) en la configuración de la app en Firebase.
- Descarga el `google-services.json` desde Firebase (Ajustes del proyecto > General > Tus apps > Android) y colócalo en `app/google-services.json`.
- En Firebase Console, habilita el proveedor "Google" en Authentication > Sign-in method.
- Sincroniza Gradle y vuelve a ejecutar la app.

Notas:
- El repo ya incluye dependencias de `play-services-auth` y `firebase-auth-ktx`.
- El plugin `com.google.gms.google-services` se aplica automáticamente si existe `app/google-services.json`.
- Si corres sin `google-services.json`, el flujo intentará usar solo Google Sign-In, pero verás error 10 si no existe un OAuth Client configurado en Google Cloud para el paquete+SHA1. Lo recomendado es usar Firebase como arriba.

## Conectar Chat a OpenAI con backend local

Nunca pongas tu API key en la app Android. Usa un backend simple (incluido en `server/`) y la app llama ahí.

Backend (Node + Express)
- Requisitos: Node 18+
- Pasos:
  1. `cd server`
  2. `npm install`
  3. Exporta tu key en el entorno: `setx OPENAI_API_KEY "sk-..."` (Windows PowerShell: `$env:OPENAI_API_KEY="sk-..."`)
  4. `npm start` (levanta en `http://localhost:3000`)

App Android
- Edita `app/src/main/res/values/strings.xml` y pon tu IP de PC en `backend_url` (ej. `http://192.168.1.82:3000`).
- Construye/instala: `./gradlew.bat :app:installDebug`
- Al enviar un mensaje en Chat, la app hará POST a `/api/chat` y mostrará la respuesta.

Notas
- El backend usa `openai` oficial y el modelo `gpt-4o-mini` para abaratar tests.
- Puedes desplegar el backend en Firebase Functions/Cloud Run cuando quieras; la app no cambia, solo el `backend_url`.

## Whisper on‑device (whisper.cpp)

Este repo incluye un andamiaje (scaffold) para integrar whisper.cpp de forma nativa en Android, sin romper la build cuando no están las fuentes del proyecto de terceros.

Pasos para activarlo:

1) Coloca las fuentes de whisper.cpp en:

   `app/src/main/cpp/third_party/whisper.cpp`

   Puedes copiar los archivos o agregarlo como submódulo. Se requieren al menos `whisper.cpp`, `ggml.c` y headers relacionados.

2) Añade el modelo base a assets:

   `app/src/main/assets/models/ggml-base.bin`

   Descarga de los enlaces oficiales de whisper.cpp (o mirrors en Hugging Face).

3) Habilita la opción de CMake para compilar con whisper.cpp:

   - En Android Studio: External Native Build > CMake arguments: `-DUSE_WHISPERCPP=ON`
   - O en `gradle.properties` agrega:

         android.defaults.cmake.arguments=-DUSE_WHISPERCPP=ON

4) Compila en un dispositivo `arm64-v8a`.

Puente JNI expuesto (inicialmente en modo stub hasta enlazar whisper.cpp):
- `nativeInitModel(modelPath: String, translate: Boolean, threads: Int): Boolean`
- `nativeTranscribeShort(pcm: ShortArray, sampleRate: Int): String`

Wrapper Kotlin: `org.psyhackers.mashia.stt.WhisperEngine`.

Cuando las fuentes estén presentes y `USE_WHISPERCPP=ON`, completa el wiring en `app/src/main/cpp/WhisperBridge.cpp` para cargar el modelo y ejecutar inferencia.

## Crear remoto en GitHub

Con GitHub CLI (`gh`) autenticado:

```
gh repo create rophiroth/MashIA --source . --public --push
```

O crea el repo en GitHub y luego:

```
git remote add origin https://github.com/rophiroth/MashIA.git
git push -u origin main
```
