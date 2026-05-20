<template>
  <div class="w-full h-20 bg-bg relative">
    <div id="bookshelf-navbar" class="absolute z-10 top-0 left-0 w-full h-full flex items-center gap-3 bg-secondary/95 px-4">
      <nuxt-link v-for="item in items" :key="item.to" :to="item.to" class="h-16 min-w-0 flex-grow flex items-center justify-center gap-2 rounded-2xl border px-3" :class="routeName === item.routeName ? 'bg-primary border-border text-fg shadow-sm active-tab' : 'text-fg-muted border-transparent hover:bg-bg-hover/30'">
        <span v-if="item.iconPack === 'abs-icons'" class="abs-icons flex-shrink-0" :class="`icon-${item.icon} ${item.iconClass || ''}`"></span>
        <span v-else class="flex-shrink-0" :class="`${item.iconPack} ${item.iconClass || ''}`">{{ item.icon }}</span>
        <p class="text-base font-semibold truncate">{{ item.text }}</p>
      </nuxt-link>
    </div>
  </div>
</template>

<script>
export default {
  data() {
    return {}
  },
  computed: {
    currentLibrary() {
      return this.$store.getters['libraries/getCurrentLibrary']
    },
    currentLibraryIcon() {
      return this.currentLibrary?.icon || 'database'
    },
    userHasPlaylists() {
      return this.$store.state.libraries.numUserPlaylists
    },
    userIsAdminOrUp() {
      return this.$store.getters['user/getIsAdminOrUp']
    },
    items() {
      let items = []
      if (this.isPodcast) {
        items = [
          {
            to: '/bookshelf',
            routeName: 'bookshelf',
            iconPack: 'abs-icons',
            icon: 'home',
            iconClass: 'text-xl',
            text: this.$strings.ButtonHome
          },
          {
            to: '/bookshelf/latest',
            routeName: 'bookshelf-latest',
            iconPack: 'abs-icons',
            icon: 'list',
            iconClass: 'text-xl',
            text: this.$strings.ButtonLatest
          },
          {
            to: '/bookshelf/library',
            routeName: 'bookshelf-library',
            iconPack: 'abs-icons',
            icon: this.currentLibraryIcon,
            iconClass: 'text-lg',
            text: this.$strings.ButtonLibrary
          }
        ]

        if (this.userIsAdminOrUp) {
          items.push({
            to: '/bookshelf/add-podcast',
            routeName: 'bookshelf-add-podcast',
            iconPack: 'material-symbols',
            icon: 'podcasts',
            iconClass: 'text-xl',
            text: this.$strings.ButtonAdd
          })
        }
      } else {
        items = [
          {
            to: '/bookshelf',
            routeName: 'bookshelf',
            iconPack: 'abs-icons',
            icon: 'home',
            iconClass: 'text-xl',
            text: this.$strings.ButtonHome
          },
          {
            to: '/bookshelf/library',
            routeName: 'bookshelf-library',
            iconPack: 'abs-icons',
            icon: this.currentLibraryIcon,
            iconClass: 'text-lg',
            text: this.$strings.ButtonLibrary
          },
          {
            to: '/bookshelf/series',
            routeName: 'bookshelf-series',
            iconPack: 'abs-icons',
            icon: 'columns',
            iconClass: 'text-lg pt-px',
            text: this.$strings.ButtonSeries
          },
          {
            to: '/bookshelf/collections',
            routeName: 'bookshelf-collections',
            iconPack: 'material-symbols',
            icon: 'collections_bookmark',
            iconClass: 'text-xl',
            text: this.$strings.ButtonCollections
          },
          {
            to: '/bookshelf/authors',
            routeName: 'bookshelf-authors',
            iconPack: 'abs-icons',
            icon: 'authors',
            iconClass: 'text-2xl',
            text: this.$strings.ButtonAuthors
          }
        ]
      }

      if (this.userHasPlaylists) {
        items.push({
          to: '/bookshelf/playlists',
          routeName: 'bookshelf-playlists',
          iconPack: 'material-symbols',
          icon: 'queue_music',
          iconClass: 'text-2xl',
          text: this.$strings.ButtonPlaylists
        })
      }

      return items
    },
    routeName() {
      return this.$route.name
    },
    isPodcast() {
      return this.libraryMediaType == 'podcast'
    },
    libraryMediaType() {
      return this.$store.getters['libraries/getCurrentLibraryMediaType']
    }
  },
  methods: {
    isSelected(item) {}
  },
  mounted() {}
}
</script>

<style>
#bookshelf-navbar {
  border-bottom: 1px solid rgba(var(--color-border), 0.72);
  box-shadow: 0px 10px 22px rgba(0, 0, 0, 0.22);
}
#bookshelf-navbar a {
  font-size: 1.12rem;
}
#bookshelf-navbar .active-tab {
  box-shadow: inset 0 0 0 1px rgba(34, 192, 154, 0.24), 0 8px 20px rgba(0, 0, 0, 0.2);
}
</style>
