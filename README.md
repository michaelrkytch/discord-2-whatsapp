# discord2whatsapp
Two-way message forwarding between Discord and Whatsapp

WIP

#### Disclaimer
This project is meant to be used as a personal utility, and should not be used commercially
or at scale.  If you use this software you must not violate the terms of service of
[WhatsApp](https://www.whatsapp.com/legal/terms-of-service/) or [Discord](https://discord.com/terms).

## Usage

```
clojure -M:server -m discord2whatsapp.server
```

## WhatsApp integration

WhatsApp does not offer a public API.  This project uses [auties00/whatsappweb4j](https://github.com/Auties00/WhatsappWeb4j),
which interacts with WhatsApp Web as a user.  

### Device registration

*TODO Describe QR workflow*

## Discord integration

### Bot registration
*TODO*

### Chat mapping

*TODO*

## Dependencies

* [auties00/whatsappweb4j](https://github.com/Auties00/WhatsappWeb4j)
* [discljord/discljord](https://github.com/discljord/discljord)
* [johnnyjayjay/slash](https://github.com/JohnnyJayJay/slash)
