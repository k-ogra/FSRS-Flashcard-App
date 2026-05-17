[![codecov](https://codecov.io/github/k-ogra/fsrs-flashcard-app/branch/main/graph/badge.svg?token=8CU2Q747BT)](https://codecov.io/github/k-ogra/fsrs-flashcard-app)

# FSRS Flashcard App

A modern flashcard application powered by the Free Spaced Repetition Scheduler 6 (FSRS-6) algorithm, designed to help you study smarter and remember more.

## Features

### FSRS-6 Algorithm

Uses spaced repetition to predict when you'll forget a card and schedules reviews accordingly. Cards are rated on a four-point scale (Again / Hard / Good / Easy) and the algorithm schedules the next review based on your performance. Each study session presents a unified queue grouped by due date — cards due earliest appear first, with same-day cards shuffled randomly for a natural study experience.

Every card belongs to one of three states:

- **New** — cards you have never reviewed before. Introduced gradually up to your daily new card limit.
- **Learning** — cards currently being committed to memory. These reappear within the same session at short intervals until retained, and are not subject to daily caps.
- **Review** — cards you have previously learned. Surfaced at increasing intervals calculated by FSRS to keep them fresh with minimal effort.

### Media Integrated Cards

Attach images or audio to either side of a card (question and/or answer). Media is stored in AWS S3 and served via short-lived presigned URLs. Supported formats include JPEG, PNG, GIF, WebP, MP3, WAV, and OGG, with a 10 MB per-file limit.

### Deck Management

Create, rename, and delete personal decks. Each deck can be toggled between private and public visibility. Decks can also be shared directly with specific users, who can then browse or copy them into their own library — including all attached media.

### Study Progress & Daily Limits

Daily study limits prevent card overload — new cards and reviews are capped per day according to per-user settings (defaults: 20 new cards/day, 200 reviews/day). Progress resets at midnight each day. Learning and relearning cards are always surfaced regardless of daily caps.

### Customizable Settings

Per-user settings control the daily new card limit, daily review limit, and the review-ahead window (how many minutes ahead of due time a card becomes eligible). All settings are persisted per account.

### Account Management

Register and log in with a username and password. Authentication is session-based with CSRF protection. Deleting your account permanently removes all decks, flashcards, and associated media from both the database and S3.

## Demo

https://github.com/user-attachments/assets/2fc8322e-23d0-43a2-82f8-9b09c6765a68

## Tech Stack

* [![React][React-logo]][React-url]
* [![Spring Boot][Spring-logo]][Spring-url]
* [![Postgres][Postgres-logo]][Postgres-url]
* [![AWS S3][AWS-logo]][AWS-url]

## Getting Started

TODO

## Acknowledgments
* [Java FSRS Library](https://github.com/open-spaced-repetition/java-fsrs/) — the open-source Java implementation of the FSRS algorithm used in this project for scheduling card reviews.
* [Deep Dive into FSRS](https://expertium.github.io/Algorithm.html) — an excellent technical breakdown of the FSRS algorithm and the research behind it.
* [Anki](https://apps.ankiweb.net/) — the open-source flashcard app that inspired this project.

## License

MIT. See `LICENSE` for more information.


<!-- MARKDOWN LINKS & IMAGES -->
[React-url]: https://reactjs.org/
[React-logo]: https://img.shields.io/badge/React-20232A?style=for-the-badge&logo=react&logoColor=61DAFB
[Spring-url]: https://spring.io/projects/spring-boot
[Spring-logo]: https://img.shields.io/badge/SpringBoot-6DB33F?style=flat-square&logo=Spring&logoColor=white
[Postgres-url]: https://www.postgresql.org/ 
[Postgres-logo]: https://img.shields.io/badge/postgresql-4169e1?style=for-the-badge&logo=postgresql&logoColor=white
[AWS-url]: https://aws.amazon.com/
[AWS-logo]: https://img.shields.io/badge/S3-AWS-%23FF9900?style=for-the-badge&logo=amazonaws&logoColor=white
