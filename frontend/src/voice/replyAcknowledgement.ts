export function spokenLanguage(text: string): "ru" | "en" {
  return /[а-яё]/i.test(text) ? "ru" : "en";
}

export function replyAcknowledgement(text: string) {
  const language = spokenLanguage(text);
  const message = text.trim();
  if (language === "ru") {
    if (/^(талия|привет)[\s!?.,]*$/i.test(message)) return { language, text: "Я здесь!" };
    if (/погод|прогноз/i.test(message)) {
      return { language, text: /завтр/i.test(message) ? "Уточняю прогноз на завтра." : "Уточняю прогноз погоды." };
    }
    return { language, text: "Секунду." };
  }
  if (/^(talia|hi|hello)[\s!?.,]*$/i.test(message)) return { language, text: "I'm here!" };
  if (/\b(weather|forecast)\b/i.test(message)) {
    return { language, text: /\btomorrow\b/i.test(message) ? "I'll check tomorrow's forecast." : "I'll check the weather." };
  }
  return { language, text: "One moment." };
}
