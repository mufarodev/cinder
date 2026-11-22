package com.discord.loader.mana

object ManaShowcasePage {
    const val REF_HEADER_SPARKLE = "showcase.header.sparkle"
    const val REF_TURBO_TOGGLE = "showcase.toggle.turbo"
    const val REF_TURBO_STATUS = "showcase.value.turbo"
    const val REF_RADIO_PROFILE = "showcase.radio.profile"
    const val REF_PROFILE_VALUE = "showcase.value.profile"
    const val REF_SEGMENT_DENSITY = "showcase.segment.density"
    const val REF_DENSITY_VALUE = "showcase.value.density"
    const val REF_SEARCH_INPUT = "showcase.input.search"
    const val REF_SEARCH_PREVIEW = "showcase.text.search_preview"
    const val REF_CLEAR_SEARCH_BTN = "showcase.btn.clear_search"
    const val REF_COUNTER_BADGE = "showcase.badge.counter"
    const val REF_INC_BTN = "showcase.btn.inc"
    const val REF_RESET_BTN = "showcase.btn.reset"
    const val REF_NESTED_NAV = "showcase.nav.nested"
    const val REF_OPEN_SHEET = "showcase.action.sheet"
    const val REF_OPEN_DIALOG = "showcase.action.dialog"
    const val REF_OPEN_TOAST = "showcase.action.toast"

    const val REF_CHECKBOX = "showcase.checkbox.haptics"
    const val REF_SLIDER = "showcase.slider.throttle"
    const val REF_TEXT_INPUT = "showcase.input.nickname"
    const val REF_CALLOUT_INFO = "showcase.callout.info"
    const val REF_AVATAR = "showcase.avatar.profile"
    const val REF_GHOST_BTN = "showcase.btn.ghost"
    const val REF_LINK_BTN = "showcase.btn.link"
    const val REF_OPEN_ACTION_SHEET = "showcase.action.action_sheet"

    const val REF_NESTED_TOGGLE = "showcase.nested.toggle"
    const val REF_NESTED_POP_BTN = "showcase.nested.pop"
    const val REF_NESTED_POP_ROOT_BTN = "showcase.nested.pop_root"

    const val REF_SHEET_TOGGLE = "showcase.sheet.toggle"
    const val REF_SHEET_DIALOG_BTN = "showcase.sheet.open_dialog"

    private val DENSITY_LABELS = listOf("Compact", "Cozy", "Spacious")

    @JvmStatic
    fun create(): ManaPage =
        manaPage(
            title = "Mana Showcase",
            subtitle = "Native Mana DSL & 120Hz Motion",
            titleAccessibilityId = "cinder-mana-showcase-title",
        ) {
            val turboMode = booleanState(true)
            val hapticsEnabled = booleanState(true)
            val throttleMs = intState(250)
            val motionProfile = stringState("high")
            val densityIndex = intState(1)
            val searchQuery = stringState("")
            val nickname = stringState("Cinder")
            val nicknameError = manaState<String?>(null)
            val counter = intState(0)

            headerAction(
                icon = ManaIcons.SPARKLES,
                label = "Show toast",
                accessibilityId = "cinder.showcase.sparkle",
                refKey = REF_HEADER_SPARKLE,
            ) {
                context.showToast(
                    message = "Mana SDK v1 · ${ThemeEngine.currentPreset().name} theme",
                    icon = ManaIcons.SPARKLES,
                    tone = ManaToastTone.POSITIVE,
                )
            }

            section(
                title = "Reactive Controls & 120Hz Motion",
                footer = "Switches & checkboxes run natively via builtin::sampled OP_MOTION; sliders bind directly to Android SeekBar.",
            ) {
                toggleRow(
                    title = "120Hz Native Spring Mode",
                    subtitle = "Driven by builtin::sampled worklet in ViewWire",
                    icon = ManaIcons.SPEEDOMETER,
                    state = turboMode,
                    accessibilityId = "cinder.showcase.turbo",
                    refKey = REF_TURBO_TOGGLE,
                )
                checkboxRow(
                    title = "Haptic Micro-Feedback",
                    subtitle = "24x24 rounded Mana checkbox with 120Hz spring",
                    state = hapticsEnabled,
                    icon = ManaIcons.SPARKLES,
                    accessibilityId = "cinder.showcase.haptics",
                    refKey = REF_CHECKBOX,
                )
                sliderRow(
                    title = "Throttle Delay",
                    range = 0..1000,
                    step = 50,
                    state = throttleMs,
                    valueFormatter = { v -> "$v ms" },
                    subtitle = "Native Phoenix type=\"slider\" SeekBar",
                    accessibilityId = "cinder.showcase.throttle",
                    refKey = REF_SLIDER,
                )
                valueRow(
                    title = "Derived Status",
                    value = turboMode.map(scope) { on -> if (on) "Active · 120Hz" else "Paused" },
                    icon = ManaIcons.CHECKMARK_LARGE,
                    refKey = REF_TURBO_STATUS,
                )
            }

            section(
                title = "Native Radio Group",
                footer = "Differential OP_MOTION spring updates only the deselected and newly selected radio rings.",
            ) {
                radioGroup(
                    options =
                        listOf(
                            RadioOption("standard", "Standard", "Balanced 60Hz animation curve", "cinder.showcase.radio.standard"),
                            RadioOption("high", "High Refresh (120Hz)", "Native UIManager spring physics", "cinder.showcase.radio.high"),
                            RadioOption("ultra", "Ultra Low-Latency", "Snappy micro-interaction response", "cinder.showcase.radio.ultra"),
                        ),
                    selected = motionProfile,
                    refKey = REF_RADIO_PROFILE,
                )
                valueRow(
                    title = "Selected Profile",
                    value =
                        motionProfile.map(scope) { id ->
                            when (id) {
                                "standard" -> "Standard"
                                "ultra" -> "Ultra Low-Latency"
                                else -> "High Refresh (120Hz)"
                            }
                        },
                    icon = ManaIcons.FILTERS_HORIZONTAL,
                    refKey = REF_PROFILE_VALUE,
                )
            }

            section(title = "Segmented Control") {
                segmentedControl(
                    labels = DENSITY_LABELS,
                    selectedIndex = densityIndex,
                    refKey = REF_SEGMENT_DENSITY,
                )
                valueRow(
                    title = "Active Density",
                    value =
                        densityIndex.map(scope) { idx ->
                            DENSITY_LABELS[idx.coerceIn(0, DENSITY_LABELS.lastIndex)]
                        },
                    icon = ManaIcons.PAINT_PALETTE,
                    refKey = REF_DENSITY_VALUE,
                )
            }

            section(title = "Inputs, Callouts & Avatars") {
                avatar(
                    initials = "CM",
                    size = 44,
                    badgeTone = AvatarBadgeTone.ONLINE,
                    label = "Cinder Mobile",
                    subtitle = "Native Mana UI SDK · ${ThemeEngine.currentPreset().name}",
                    refKey = REF_AVATAR,
                )
                callout(
                    title = "Live Theme-Aware Surface",
                    text =
                        "Callouts, avatars, checkboxes, sliders, and inputs automatically re-skin " +
                            "when switching Light, Ash, Dark, or Onyx.",
                    tone = CalloutTone.INFO,
                    refKey = REF_CALLOUT_INFO,
                )
                textInput(
                    label = "Display Nickname",
                    placeholder = "Enter at least 3 characters...",
                    state = nickname,
                    helperText = "Live two-way ViewWire input with inline validation",
                    errorState = nicknameError,
                    accessibilityId = "cinder.showcase.nickname",
                    refKey = REF_TEXT_INPUT,
                    onChange = { next ->
                        nicknameError.value =
                            if (next.isNotEmpty() && next.length < 3) {
                                "Must be at least 3 characters"
                            } else {
                                null
                            }
                    },
                )
                searchInput(
                    placeholder = "Type to filter live...",
                    state = searchQuery,
                    accessibilityId = "cinder.showcase.search",
                    refKey = REF_SEARCH_INPUT,
                    onSubmit = { submitted ->
                        context.showToast("Searched: \"$submitted\"", icon = ManaIcons.MAGNIFYING_GLASS)
                    },
                )
                text(
                    state =
                        searchQuery.map(scope) { q ->
                            if (q.isEmpty()) "Live query: (empty)" else "Live query: \"$q\" (${q.length} chars)"
                        },
                    style = TextStyle.CAPTION,
                    tone = ManaTone.MUTED,
                    refKey = REF_SEARCH_PREVIEW,
                )
                button(
                    label = "Clear Search",
                    icon = ManaIcons.X_SMALL,
                    style = ButtonStyle.SECONDARY,
                    refKey = REF_CLEAR_SEARCH_BTN,
                ) {
                    searchQuery.value = ""
                }
            }

            section(title = "Buttons, Badges & Navigation") {
                badge(
                    state = counter.map(scope) { n -> "Clicks: $n" },
                    tone = BadgeTone.BRAND,
                    refKey = REF_COUNTER_BADGE,
                )
                button(
                    label = "Increment Counter",
                    icon = ManaIcons.PLUS_MEDIUM,
                    style = ButtonStyle.PRIMARY,
                    refKey = REF_INC_BTN,
                ) {
                    counter.update { it + 1 }
                }
                button(
                    label = "Reset Counter",
                    icon = ManaIcons.TRASH,
                    style = ButtonStyle.DANGER,
                    refKey = REF_RESET_BTN,
                ) {
                    counter.value = 0
                    context.showToast("Counter reset", icon = ManaIcons.TRASH, tone = ManaToastTone.DEFAULT)
                }
                button(
                    label = "Ghost Action (Enabled When Turbo ON)",
                    icon = ManaIcons.SPARKLES,
                    style = ButtonStyle.GHOST,
                    enabledState = turboMode,
                    refKey = REF_GHOST_BTN,
                ) {
                    context.showToast("Ghost button tapped", icon = ManaIcons.SPARKLES)
                }
                button(
                    label = "Learn More (Link Style)",
                    icon = ManaIcons.LINK_EXTERNAL_SMALL,
                    style = ButtonStyle.LINK,
                    refKey = REF_LINK_BTN,
                ) {
                    context.showToast("Cinder Mobile Mana UI SDK", icon = ManaIcons.LINK)
                }
                navRow(
                    title = "Nested Detail Page",
                    subtitle = "Push 3rd-level screen & test native swipe-back",
                    icon = ManaIcons.GLOBE_EARTH,
                    trailingState = counter.map(scope) { n -> "#$n" },
                    accessibilityId = "cinder.showcase.nested",
                    refKey = REF_NESTED_NAV,
                ) {
                    context.push(buildNestedDetailPage(counter.value))
                }
            }

            section(
                title = "Native Overlays",
                footer = "Hardware Back follows strict priority: Dialog -> Sheet -> Top Page -> Discord.",
            ) {
                actionRow(
                    title = "Present Bottom Sheet",
                    subtitle = "Native type=\"sheet\" with spring open/close & swipe-to-dismiss",
                    icon = ManaIcons.APPS,
                    accessibilityId = "cinder.showcase.sheet",
                    refKey = REF_OPEN_SHEET,
                ) {
                    presentDemoSheet(context, turboMode)
                }
                actionRow(
                    title = "Present Action Sheet",
                    subtitle = "Concise option picker & cancel button built on ManaSheet",
                    icon = ManaIcons.FILTERS_HORIZONTAL,
                    accessibilityId = "cinder.showcase.action_sheet",
                    refKey = REF_OPEN_ACTION_SHEET,
                ) {
                    presentDemoActionSheet(context, counter)
                }
                actionRow(
                    title = "Show Confirm Dialog",
                    subtitle = "Native full-window type=\"modal\" dialog with open/close animations",
                    icon = ManaIcons.WARNING,
                    accessibilityId = "cinder.showcase.dialog",
                    refKey = REF_OPEN_DIALOG,
                ) {
                    showDemoDialog(context, counter)
                }
                actionRow(
                    title = "Show Toast Banner",
                    subtitle = "Floating pill banner with automatic dismissal",
                    icon = ManaIcons.BELL,
                    accessibilityId = "cinder.showcase.toast",
                    refKey = REF_OPEN_TOAST,
                ) {
                    context.showToast(
                        message = "Native Mana toast (clicks=${counter.value})",
                        icon = ManaIcons.CHECKMARK_LARGE,
                        tone = ManaToastTone.POSITIVE,
                    )
                }
            }
        }

    private fun buildNestedDetailPage(capturedClicks: Int): ManaPage =
        manaPage(
            title = "Nested Detail",
            subtitle = "Level 3 · Native Navigator Stack",
            titleAccessibilityId = "cinder-mana-nested-title",
        ) {
            val nestedSwitch = booleanState(false)

            section(
                title = "Deep Navigation",
                footer = "Swipe from the left edge, tap Back, or pop straight back to Settings Overview.",
            ) {
                valueRow(
                    title = "Captured Counter",
                    value = capturedClicks.toString(),
                    icon = ManaIcons.SPARKLES,
                )
                valueRow(
                    title = "Active Theme Preset",
                    value = ThemeEngine.currentPreset().name,
                    icon = ManaIcons.PAINT_PALETTE,
                )
                toggleRow(
                    title = "Nested Page Switch",
                    subtitle = "Independent scope; slots freed on pop",
                    state = nestedSwitch,
                    refKey = REF_NESTED_TOGGLE,
                )
            }

            section(title = "Stack Controls") {
                button(
                    label = "Pop to Showcase",
                    icon = ManaIcons.ARROW_LARGE_LEFT,
                    style = ButtonStyle.SECONDARY,
                    refKey = REF_NESTED_POP_BTN,
                ) {
                    context.pop()
                }
                button(
                    label = "Pop to Settings Overview",
                    icon = ManaIcons.X_SMALL,
                    style = ButtonStyle.DANGER,
                    refKey = REF_NESTED_POP_ROOT_BTN,
                ) {
                    context.popToRoot()
                }
            }
        }

    private fun presentDemoSheet(
        parentContext: ManaContext,
        sharedTurbo: ManaState<Boolean>,
    ) {
        parentContext.presentSheet(
            manaSheet(
                title = "Mana Bottom Sheet",
                subtitle = "Synced live with parent page state",
            ) {
                toggleRow(
                    title = "120Hz Native Spring Mode",
                    subtitle = "Bound to the same ManaState<Boolean>",
                    icon = ManaIcons.SPEEDOMETER,
                    state = sharedTurbo,
                    refKey = REF_SHEET_TOGGLE,
                )
                button(
                    label = "Stack Dialog Above Sheet",
                    icon = ManaIcons.SPARKLES,
                    style = ButtonStyle.PRIMARY,
                    refKey = REF_SHEET_DIALOG_BTN,
                ) {
                    context.showDialog(
                        confirmDialog(
                            title = "Stacked Over Sheet",
                            message = "Pressing hardware Back dismisses this dialog first, keeping the sheet open.",
                            confirmLabel = "Got It",
                            cancelLabel = "Close",
                        ) {
                            context.showToast("Dialog confirmed", icon = ManaIcons.CHECKMARK_LARGE)
                        },
                    )
                }
            },
        )
    }

    private fun presentDemoActionSheet(
        parentContext: ManaContext,
        counter: ManaState<Int>,
    ) {
        parentContext.presentActionSheet(
            title = "Quick Actions",
            subtitle = "Choose an action for the showcase counter",
        ) {
            option("Add +10", icon = ManaIcons.PLUS_MEDIUM) {
                counter.update { it + 10 }
                parentContext.showToast("Counter +10", icon = ManaIcons.CHECK, tone = ManaToastTone.POSITIVE)
            }
            option("Reset to Zero", icon = ManaIcons.TRASH, danger = true) {
                counter.value = 0
                parentContext.showToast("Counter cleared", icon = ManaIcons.TRASH)
            }
            cancelButton("Cancel")
        }
    }

    private fun showDemoDialog(
        parentContext: ManaContext,
        counter: ManaState<Int>,
    ) {
        parentContext.showDialog(
            confirmDialog(
                title = "Reset Showcase Counter?",
                message = "Current count is ${counter.value}. Confirming resets it to 0 and shows a toast.",
                confirmLabel = "Reset",
                cancelLabel = "Cancel",
                destructive = true,
            ) {
                counter.value = 0
                parentContext.showToast("Reset via dialog", icon = ManaIcons.CHECKMARK_LARGE)
            },
        )
    }
}
