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
