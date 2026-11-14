"use strict";

const form = document.querySelector("#review-form");
const photoInput = document.querySelector("#photos");
const photoList = document.querySelector("#photo-list");
const photoStatus = document.querySelector("#photo-status");
const details = document.querySelector("#details");
const preview = document.querySelector("#preview");
const photoLimit = 5;
const photos = [];

function showPhoto(photo) {
  const item = document.createElement("li");
  const image = document.createElement("img");
  image.alt = photo.name;
  image.addEventListener("error", () => {
    const fallback = document.createElement("span");
    fallback.className = "image-fallback";
    fallback.textContent = photo.name + " (미리보기 미지원)";
    image.replaceWith(fallback);
  });
  image.src = photo.url;
  item.append(image);
  return item;
}

function updatePhotoList(message = "") {
  photoList.replaceChildren();
  for (const photo of photos) {
    const item = showPhoto(photo);
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "remove";
    remove.setAttribute("aria-label", photo.name + " 삭제");
    const icon = document.createElement("span");
    icon.textContent = "×";
    icon.setAttribute("aria-hidden", "true");
    remove.append(icon);
    remove.addEventListener("click", () => {
      photos.splice(photos.indexOf(photo), 1);
      URL.revokeObjectURL(photo.url);
      updatePhotoList("사진을 삭제했어요.");
      photoInput.focus();
    });
    item.append(remove);
    photoList.append(item);
  }
  photoStatus.textContent = [message, photos.length ? photos.length + "장 선택됨" : ""].filter(Boolean).join(" ");
}

photoInput.addEventListener("change", () => {
  const messages = new Set();
  for (const file of photoInput.files) {
    if (photos.length === photoLimit) {
      messages.add("사진은 5장까지 고를 수 있어요.");
      break;
    }
    const imageType = file.type.startsWith("image/") ||
      (!file.type && /\.(avif|bmp|gif|heic|heif|jpe?g|png|tiff?|webp)$/i.test(file.name));
    if (!imageType) {
      messages.add("사진 파일만 선택할 수 있어요.");
      continue;
    }
    photos.push({ name: file.name, url: URL.createObjectURL(file) });
  }
  photoInput.value = "";
  updatePhotoList([...messages].join(" "));
});

function updateStars() {
  const score = Number(form.elements.rating.value);
  for (const radio of form.elements.rating) {
    const filled = Number(radio.value) <= score;
    const star = radio.nextElementSibling;
    star.textContent = filled ? "★" : "☆";
    star.classList.toggle("filled", filled);
  }
}
for (const radio of form.elements.rating) radio.addEventListener("change", updateStars);
details.addEventListener("input", () => details.setCustomValidity(""));

form.addEventListener("submit", event => {
  event.preventDefault();
  if (!details.value.trim()) {
    details.setCustomValidity("차박 후기를 입력해 주세요.");
    details.reportValidity();
    return;
  }
  const rating = Number(form.elements.rating.value);
  document.querySelector("#preview-rating").textContent = "★".repeat(rating) + " · " + rating + "점";
  const place = document.querySelector("#spot").value.trim();
  const previewSpot = document.querySelector("#preview-spot");
  previewSpot.textContent = place;
  previewSpot.hidden = !place;
  document.querySelector("#preview-details").textContent = details.value.trim();
  document.querySelector("#preview-photos").replaceChildren(...photos.map(showPhoto));
  form.hidden = true;
  preview.hidden = false;
  document.querySelector("#preview-title").focus();
});

document.querySelector("#edit").addEventListener("click", () => {
  preview.hidden = true;
  document.querySelector("#preview-photos").replaceChildren();
  form.hidden = false;
  details.focus();
});

form.reset();
updateStars();
form.hidden = false;
