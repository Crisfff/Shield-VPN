const APP_DOWNLOAD_URL = "https://firebasestorage.googleapis.com/v0/b/ido-pay-2b46e.appspot.com/o/app%2Fshield-vpn.apk?alt=media&token=a7f9971e-d242-484c-93f3-c758d9b2a599";

const menuBtn = document.getElementById("menuBtn");
const navLinks = document.getElementById("navLinks");

const downloadButtons = document.querySelectorAll(".download-app");

downloadButtons.forEach((button) => {
  button.setAttribute("href", APP_DOWNLOAD_URL);
  button.setAttribute("target", "_blank");
  button.setAttribute("rel", "noopener noreferrer");
});

if (menuBtn && navLinks) {
  menuBtn.addEventListener("click", () => {
    navLinks.classList.toggle("active");
    menuBtn.classList.toggle("active");
  });

  navLinks.querySelectorAll("a").forEach((link) => {
    link.addEventListener("click", () => {
      navLinks.classList.remove("active");
      menuBtn.classList.remove("active");
    });
  });
}

window.addEventListener("scroll", () => {
  const header = document.querySelector(".header");

  if (!header) return;

  if (window.scrollY > 30) {
    header.classList.add("scrolled");
  } else {
    header.classList.remove("scrolled");
  }
});

const revealElements = document.querySelectorAll(
  ".hero-text, .hero-visual, .stat-card, .benefit-card, .security-content, .security-panel, .plan-card, .cta"
);

const revealObserver = new IntersectionObserver(
  (entries) => {
    entries.forEach((entry) => {
      if (entry.isIntersecting) {
        entry.target.classList.add("show");
      }
    });
  },
  {
    threshold: 0.14,
  }
);

revealElements.forEach((element) => {
  element.classList.add("reveal");
  revealObserver.observe(element);
});
