# Control1 — pilot Bluetooth dla Android TV

Prototyp aplikacji na telefon z Androidem, która zgłasza się telewizorowi jako
urządzenie Bluetooth HID: klawiatura i pilot multimedialny.

## Wymagania

- fizyczny telefon z Androidem 9 (API 28) lub nowszym,
- telewizor obsługujący klawiaturę Bluetooth,
- telefon z aktywnym profilem Bluetooth HID Device.

## Uruchomienie

1. Sklonuj repozytorium i otwórz je w Android Studio.
2. Poczekaj na synchronizację Gradle.
3. Uruchom aplikację na fizycznym telefonie.
4. W aplikacji wybierz **Parowanie** i sparuj telefon z telewizorem.
5. Wróć do aplikacji, wybierz telewizor z listy i naciśnij **Połącz**.
6. Przetestuj strzałki i OK, a następnie wysyłanie tekstu.

Emulator zwykle nie obsługuje trybu Bluetooth HID Device. Wysyłanie tekstu
korzysta obecnie z układu US HID; znaki ASCII działają, natomiast polskie znaki
są pomijane. Obsługa Home i Wstecz zależy od mapowania Consumer Control w
konkretnym telewizorze.

## Gdy połączenie nie działa

- usuń parowanie po obu stronach i sparuj urządzenia ponownie,
- upewnij się, że telewizor przyjmuje połączenia zwykłej klawiatury Bluetooth,
- pozostaw aplikację otwartą podczas testu,
- sprawdź, czy producent telefonu nie wyłączył profilu HID Device.
