# monolit

`monolit` — каркас Spring Boot-приложения с VK Long Poll, клавиатурами,
маршрутизацией действий по payload и отправкой текста и фотографий.
Сохранён новостной функционал Cherinfo: RSS, полный текст статей и изображения.

## Запуск

Требуются Java 21, Maven и PostgreSQL с подготовленной БД.
Перед запуском задайте переменные окружения:

- `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` — подключение к БД.
- `VK_GROUP_ID`, `VK_GROUP_TOKEN` — группа VK и её токен.
- `VK_MY_ID` — ID пользователя, от которого бот принимает сообщения.

```bash
mvn -B clean verify
java -jar target/monolit-0.0.1-SNAPSHOT.jar
```

Для запуска из исходников: `mvn spring-boot:run`.

При запуске появляется клавиатура; новости Cherinfo отправляются по кнопке «Получить новости».
Таблицы создаются и обновляются через Hibernate `ddl-auto=update`.

Интеграция с ChatGPT включена по умолчанию. Адрес REST —
в [application.properties](src/main/resources/application.properties).
Для Docker нужна существующая сеть `cli-to-chat` от Compose REST-сервиса.
Кнопка «ChatGPT» включает режим; «Выход» возвращает обычный режим, сохраняя историю.
В режиме ChatGPT кнопка «Новый чат» начинает отдельный диалог со следующего сообщения.
Активный диалог сохраняется в БД и продолжается после перезапуска бота; старые диалоги остаются в сервисе чата.
ID диалогов генерируются автоматически. Пока выполняется запрос, переключение недоступно.
Кнопка «Получить новости» доступна только в главном меню.
«Мышление» показывает текущие настройки и позволяет выбрать `low`, `medium`, `high` или `xhigh`, сохраняя модель и
историю.
Настройка доступна после первого сообщения в новом чате; поддерживаемые уровни определяет сервис чата.

## Docker и GitHub Actions

Для сборки образа используйте [Dockerfile](Dockerfile).
Настройки PostgreSQL и Docker-сети в [compose.yaml](compose.yaml) и
[скрипте CD](.github/scripts/deploy.sh) адаптируйте под своё окружение. HTTP `/actuator/health` доступен только на
`127.0.0.1:8080` внутри контейнера и учитывает доступность БД.

[Java CI](.github/workflows/sonarqube.yml) собирает проект и выполняет анализ SonarQube Cloud.
Для него нужны secret `SONAR_TOKEN` и variables `SONAR_ORGANIZATION`, `SONAR_PROJECT_KEY`
со значениями вашего проекта.

[CD](.github/workflows/deploy.yml) запускается при push в `main`.
Нужен self-hosted runner с метками `self-hosted`, `ci`, Docker Compose
и доступом к Docker socket. В GitHub Actions настройте:

- Secrets: `POSTGRES_ADMIN_PASSWORD`, `SPRING_DATASOURCE_PASSWORD`, `VK_GROUP_TOKEN`.
- Variables: `VK_GROUP_ID`, `VK_MY_ID`, `POSTGRES_NETWORK`.

CD создаёт отсутствующие БД и роль приложения, проверяет подключение,
собирает образ и ждёт `healthy`. Подключение бота к VK проверяется отдельно.

## License

This repository is distributed under the **PolyForm Noncommercial License 1.0.0**.

- Personal and other noncommercial use is allowed.
- Commercial use is not allowed without prior written permission from the copyright holder.

Because of the noncommercial restriction, this is a source-available license model (not OSI open source).

Full license text: [LICENSE](LICENSE)
