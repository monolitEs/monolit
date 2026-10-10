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

Конкретных обработчиков бизнес-действий и меню в каркасе нет.
Новости Cherinfo публикуются при запуске и каждый час. Без сохранённой даты берутся
пять последних новостей; после перезапуска дата восстанавливается из PostgreSQL.
Отключение: `MONOLIT_NEWS_CHERINFO_ENABLED=false`.
Таблицы создаются и обновляются через Hibernate `ddl-auto=update`.

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
