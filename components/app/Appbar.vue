<template>
  <div class="w-full h-24 bg-primary relative z-20 shelfdrive-topbar">
    <div id="appbar" class="absolute top-0 left-0 w-full h-full flex items-center gap-4 px-5">
      <nuxt-link v-show="!showBack" to="/" class="h-20 pr-5 rounded-2xl flex items-center gap-4 hover:bg-bg-hover/40" style="min-width: 5rem" aria-label="ShelfDrive home">
        <img src="/shelfdrive-logo.svg" class="h-14 w-14 shelfdrive-logo" />
        <span class="hidden sm:inline text-2xl font-semibold tracking-normal text-fg">ShelfDrive</span>
      </nuxt-link>
      <a v-if="showBack" @click="back" aria-label="Back" class="rounded-2xl h-16 w-16 flex items-center justify-center cursor-pointer bg-secondary/70 border border-border shadow-sm hover:bg-bg-hover/40">
        <span class="material-symbols text-4xl text-fg">arrow_back</span>
      </a>
      <div v-if="user && currentLibrary">
        <button type="button" aria-label="Show library modal" class="h-16 max-w-[20rem] pl-4 pr-6 bg-secondary/90 border border-border rounded-2xl flex items-center shadow-sm hover:bg-bg-hover/40" @click="clickShowLibraryModal">
          <ui-library-icon :icon="currentLibraryIcon" :size="6" font-size="2xl" />
          <p class="text-xl leading-6 ml-4 max-w-[13rem] truncate">{{ currentLibraryName }}</p>
        </button>
      </div>

      <widgets-connection-indicator />

      <div class="flex-grow" />

      <widgets-download-progress-indicator />

      <nuxt-link v-if="user" class="flex items-center justify-center h-16 w-16 rounded-2xl bg-secondary/70 border border-border shadow-sm hover:bg-bg-hover/40" to="/search" aria-label="Search">
        <span class="material-symbols text-4xl leading-none">search</span>
      </nuxt-link>

      <button type="button" aria-label="Toggle side drawer" class="h-16 w-16 rounded-2xl bg-secondary/70 border border-border shadow-sm hover:bg-bg-hover/40" @click="clickShowSideDrawer">
        <span class="material-symbols text-4xl leading-none">menu</span>
      </button>
    </div>
  </div>
</template>

<script>
export default {
  computed: {
    currentLibrary() {
      return this.$store.getters['libraries/getCurrentLibrary']
    },
    currentLibraryName() {
      return this.currentLibrary?.name || ''
    },
    currentLibraryIcon() {
      return this.currentLibrary?.icon || 'database'
    },
    showBack() {
      if (!this.$route.name) return true
      return this.$route.name !== 'index' && !this.$route.name.startsWith('bookshelf')
    },
    user() {
      return this.$store.state.user.user
    },
    username() {
      return this.user?.username || 'err'
    }
  },
  methods: {
    clickShowSideDrawer() {
      this.$store.commit('setShowSideDrawer', true)
    },
    clickShowLibraryModal() {
      this.$store.commit('libraries/setShowModal', true)
    },
    back() {
      window.history.back()
    }
  }
}
</script>

<style>
#appbar {
  background:
    linear-gradient(90deg, rgba(34, 192, 154, 0.2), transparent 34%),
    rgb(var(--color-primary));
  border-bottom: 1px solid rgba(var(--color-border), 0.8);
  box-shadow: 0px 14px 30px rgba(0, 0, 0, 0.34);
}
.loader-dots div {
  animation-timing-function: cubic-bezier(0, 1, 1, 0);
}
.loader-dots div:nth-child(1) {
  left: 0px;
  animation: loader-dots1 0.6s infinite;
}
.loader-dots div:nth-child(2) {
  left: 0px;
  animation: loader-dots2 0.6s infinite;
}
.loader-dots div:nth-child(3) {
  left: 10px;
  animation: loader-dots2 0.6s infinite;
}
.loader-dots div:nth-child(4) {
  left: 20px;
  animation: loader-dots3 0.6s infinite;
}
@keyframes loader-dots1 {
  0% {
    transform: scale(0);
  }
  100% {
    transform: scale(1);
  }
}
@keyframes loader-dots3 {
  0% {
    transform: scale(1);
  }
  100% {
    transform: scale(0);
  }
}
@keyframes loader-dots2 {
  0% {
    transform: translate(0, 0);
  }
  100% {
    transform: translate(10px, 0);
  }
}
</style>
