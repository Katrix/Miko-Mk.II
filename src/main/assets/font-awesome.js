import { library, dom, config } from '@fortawesome/fontawesome-svg-core'
import {
  faStepBackward,
  faStepForward,
  faPause,
  faPlay,
  faTimes,
  faArrowDown,
  faArrowUp,
  faPlus,
  faMinus,
} from '@fortawesome/free-solid-svg-icons'

import '@fortawesome/fontawesome-svg-core/styles.css'
config.autoAddCss = false

library.add(faStepBackward, faStepForward, faPause, faPlay, faTimes, faArrowDown, faArrowUp, faPlus, faMinus)

dom.watch()
