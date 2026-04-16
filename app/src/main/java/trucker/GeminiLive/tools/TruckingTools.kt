package trucker.GeminiLive.tools

import trucker.GeminiLive.network.FunctionDeclaration
import trucker.GeminiLive.network.Schema
import trucker.GeminiLive.network.Tool
import kotlinx.serialization.json.*

object TruckingTools {
    private const val DEMO_DRIVER_ID = "284145"
    private const val DEMO_ACTIVE_LOAD_ID = "902771"

    val declaration = Tool(
        functionDeclarations = listOf(
            FunctionDeclaration(
                name = "getDriverProfile",
                description = "Returns deterministic driver profile, current location, tractor/trailer equipment snapshot, and compliance posture from pre-authenticated session context. Invocation condition: call when the driver asks who they are, where they are, what equipment they have, or their high-level compliance status.",
                parameters = Schema(
                    type = "object",
                    properties = emptyMap()
                )
            ),
            FunctionDeclaration(
                name = "getLoadStatus",
                description = "Returns deterministic active-load progress including stop timeline, each stop status, and load-specific risks or blockers from pre-authenticated session context. Invocation condition: call when the driver asks about load status, stop ETAs, detention risk, or appointment timing.",
                parameters = Schema(
                    type = "object",
                    properties = emptyMap()
                )
            ),
            FunctionDeclaration(
                name = "getComplianceAlerts",
                description = "Returns deterministic compliance alerts and deadlines (HOS, DVIR, med card, IFTA, permits) from pre-authenticated session context. Invocation condition: call when the driver asks what compliance items are due, at risk, or out of policy.",
                parameters = Schema(
                    type = "object",
                    properties = emptyMap()
                )
            ),
            FunctionDeclaration(
                name = "getRouteRisks",
                description = "Returns deterministic route-risk intelligence for a future horizon including weather, traffic, restrictions, and stop-level risk scoring from pre-authenticated session context.",
                parameters = Schema(
                    type = "object",
                    properties = mapOf(
                        "timeHorizonHours" to Schema(type = "number", description = "Hours ahead to evaluate route risk")
                    ),
                    required = listOf("timeHorizonHours")
                )
            ),
            FunctionDeclaration(
                name = "getDispatchInbox",
                description = "Returns deterministic dispatch inbox messages and open exceptions requiring driver action from pre-authenticated session context. Invocation condition: call when the driver asks about new dispatch instructions, unresolved issues, or messages needing acknowledgment.",
                parameters = Schema(
                    type = "object",
                    properties = mapOf(
                        "unreadOnly" to Schema(type = "boolean", description = "If true, return only unread dispatch items")
                    ),
                    required = listOf("unreadOnly")
                )
            ),
            FunctionDeclaration(
                name = "getCompanyFAQs",
                description = "Returns curated deterministic Swift Transportation SOP and FAQ bundles by category. Invocation condition: call when the driver asks company-policy/procedure questions not specific to a single load state.",
                parameters = Schema(
                    type = "object",
                    properties = emptyMap()
                )
            ),
            FunctionDeclaration(
                name = "getPaycheckInfo",
                description = "Returns deterministic paycheck summary with associated miles metrics for the authenticated driver context. Invocation condition: call when the driver asks about pay, settlement amounts, CPM, gross/net totals, reimbursement, or miles tied to pay.",
                parameters = Schema(
                    type = "object",
                    properties = emptyMap()
                )
            ),
            FunctionDeclaration(
                name = "findNearestSwiftTerminal",
                description = "Returns the nearest Swift Transportation terminal or drop yard, including distance and available amenities (showers, shop, wash, parking). Invocation condition: call when the driver asks about where to park, get a truck wash, or find terminal amenities.",
                parameters = Schema(
                    type = "object",
                    properties = emptyMap()
                )
            ),
            FunctionDeclaration(
                name = "checkSafetyScore",
                description = "Returns the driver's current safety score, ranking, and recent telemetry events (e.g., hard braking, overspeeding). Invocation condition: call when the driver asks about their driving score, safety record, or bonus standing.",
                parameters = Schema(
                    type = "object",
                    properties = emptyMap()
                )
            ),
            FunctionDeclaration(
                name = "getFuelNetworkRouting",
                description = "Returns the next approved in-network fuel stop (e.g., Swift Fuel Network, Pilot/Flying J) based on current location and route. Invocation condition: call when the driver asks where they should get fuel next.",
                parameters = Schema(
                    type = "object",
                    properties = emptyMap()
                )
            )
        )
    )

    fun handleToolCall(name: String, args: Map<String, JsonElement>?): JsonElement {
        return when (name) {
            "getDriverProfile" -> {
                buildJsonObject {
                    put("driver_id", DEMO_DRIVER_ID)
                    put("profile", buildJsonObject {
                        put("full_name", "Jordan Ramirez")
                        put("fleet", "Dry Van OTR")
                        put("home_terminal", "Phoenix, AZ")
                        put("cdl_class", "A")
                        put("tenure_years", 6)
                    })
                    put("location", buildJsonObject {
                        put("as_of", "2026-04-15T14:20")
                        put("lat", 35.1983)
                        put("lon", -111.6513)
                        put("nearest_city", "Flagstaff, AZ")
                        put("corridor", "I-40 EB")
                    })
                    put("equipment", buildJsonObject {
                        put("tractor", "684821")
                        put("trailer", "903144")
                        put("trailer_type", "53ft Dry Van")
                        put("reefer_enabled", false)
                        put("eld_provider", "Samsara")
                    })
                    put("compliance", buildJsonObject {
                        put("hos_cycle_hours_remaining", 18.75)
                        put("drive_hours_remaining", 5.25)
                        put("next_30m_break_due_by", "2026-04-15T17:05")
                        put("med_card_expires_on", "2026-12-14")
                        put("dvir_status", "submitted_today")
                    })
                }
            }
            "getLoadStatus" -> {
                buildJsonObject {
                    put("driver_id", DEMO_DRIVER_ID)
                    put("load_id", DEMO_ACTIVE_LOAD_ID)
                    put("status", "in_transit")
                    put("priority", "high")
                    put("origin", "Reno, NV")
                    put("destination", "Dallas, TX")
                    put("next_stop_eta", "2026-04-15T19:40")
                    put("stops", buildJsonArray {
                        add(buildJsonObject {
                            put("stop_index", 1)
                            put("type", "pickup")
                            put("facility", "Silver State Distribution")
                            put("city", "Reno, NV")
                            put("appointment", "2026-04-14T09:00")
                            put("arrival_time", "2026-04-14T08:45")
                            put("status", "completed")
                        })
                        add(buildJsonObject {
                            put("stop_index", 2)
                            put("type", "fuel")
                            put("facility", "Swift Fuel Network #AZ-17")
                            put("city", "Flagstaff, AZ")
                            put("appointment", "2026-04-15T19:30")
                            put("arrival", "2026-04-15T19:40")
                            put("status", "in_progress")
                            put("risk", "minor_delay_10m")
                        })
                        add(buildJsonObject {
                            put("stop_index", 3)
                            put("type", "delivery")
                            put("facility", "DFW Retail Crossdock")
                            put("city", "Dallas, TX")
                            put("appointment", "2026-04-16T13:00")
                            put("status", "pending")
                            put("risk", "tight_eta_due_to_i40_winds")
                        })
                    })
                    put("route_risks", buildJsonArray {
                        add(buildJsonObject {
                            put("segment", "I-40 EB mm 167-210")
                            put("risk_type", "crosswind")
                            put("severity", "medium")
                            put("confidence", 0.86)
                        })
                        add(buildJsonObject {
                            put("segment", "US-287 Southbound")
                            put("risk_type", "construction")
                            put("severity", "low")
                            put("confidence", 0.78)
                        })
                    })
                }
            }
            "getComplianceAlerts" -> {
                buildJsonObject {
                    put("driver_id", DEMO_DRIVER_ID)
                    put("alerts", buildJsonArray {
                        add(buildJsonObject {
                            put("category", "HOS")
                            put("severity", "warning")
                            put("message", "11-hour drive limit projected in 5h 15m.")
                            put("due_by", "2026-04-15T20:05")
                        })
                        add(buildJsonObject {
                            put("category", "DVIR")
                            put("severity", "info")
                            put("message", "Pre-trip DVIR submitted. Post-trip pending.")
                            put("due_by", "2026-04-16T03:00")
                        })
                        add(buildJsonObject {
                            put("category", "IFTA")
                            put("severity", "warning")
                            put("message", "Fuel receipt photo missing for stop #2.")
                            put("due_by", "2026-04-15T23:59")
                        })
                    })
                    put("overall_status", "attention_required")
                }
            }
            "getRouteRisks" -> {
                val horizon = args?.get("timeHorizonHours")?.jsonPrimitive?.doubleOrNull ?: 6.0
                buildJsonObject {
                    put("driver_id", DEMO_DRIVER_ID)
                    put("time_horizon_hours", horizon)
                    put("generated_at", "2026-04-15T14:20")
                    put("overall_risk_level", "medium")
                    put("route_risks", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "weather_crosswind")
                            put("segment", "I-40 EB near Holbrook")
                            put("window", "2026-04-15T16:30 to 2026-04-15T19:30")
                            put("severity", "medium")
                            put("recommended_action", "Reduce speed 8-12 mph and increase following distance.")
                        })
                        add(buildJsonObject {
                            put("type", "traffic_congestion")
                            put("segment", "I-40 EB Albuquerque bypass")
                            put("window", "2026-04-15T18:10/2026-04-15T19:00")
                            put("severity", "low")
                            put("recommended_action", "Use outer bypass if average speed drops below 25 mph.")
                        })
                    })
                }
            }
            "getDispatchInbox" -> {
                val unreadOnly = args?.get("unreadOnly")?.jsonPrimitive?.booleanOrNull ?: false
                val messages = buildJsonArray {
                    add(buildJsonObject {
                        put("message_id", "DSP-77101")
                        put("unread", true)
                        put("priority", "high")
                        put("subject", "Delivery gate code updated")
                        put("body", "DFW Retail Crossdock gate code is now 4729#. Confirm receipt.")
                        put("created_at", "2026-04-15T13:55")
                    })
                    add(buildJsonObject {
                        put("message_id", "DSP-77088")
                        put("unread", false)
                        put("priority", "normal")
                        put("subject", "Fuel stop preference")
                        put("body", "Use Swift Fuel Network #AZ-17 when practical.")
                        put("created_at", "2026-04-15T09:10")
                    })
                }
                val filteredMessages = if (unreadOnly) {
                    JsonArray(messages.filter { it.jsonObject["unread"]?.jsonPrimitive?.boolean == true })
                } else {
                    messages
                }
                buildJsonObject { put("messages", filteredMessages) }
            }

            "getCompanyFAQs" -> {
                buildJsonObject {
                    put("company", "Swift Transportation")
                    put("last_updated", "2026-04-10")
                    put("categories", buildJsonArray {
                        // Dog / Pet Policy
                        add(buildJsonObject {
                            put("category", "Pet Policy")
                            put("policy_summary", "Swift allows one dog (under 75 lbs) for company drivers.")
                            put("details", buildJsonArray {
                                add("Requires a non-refundable $500 pet fee (payable via payroll deduction).")
                                add("Dog must be up to date on vaccinations.")
                                add("Restricted breeds: Pit Bulls, Rottweilers, Dobermans, Mastiffs.")
                                add("Pet must be leashed at all Swift terminals and customer locations.")
                            })
                        })
                        // Rider Policy
                        add(buildJsonObject {
                            put("category", "Rider Policy")
                            put("policy_summary", "Authorized riders are permitted with a valid permit.")
                            put("details", buildJsonArray {
                                add("Riders must be at least 12 years old.")
                                add("A small monthly insurance fee is deducted from pay.")
                                add("Permits must be renewed annually.")
                            })
                        })
                        // Breakdown / Maintenance
                        add(buildJsonObject {
                            put("category", "Breakdown SOP")
                            put("policy_summary", "Protocol for mechanical issues on the road.")
                            put("details", buildJsonArray {
                                add("Use the 'Breakdown' macro on your tablet immediately.")
                                add("Call On-Road Support (Option 4) if you are in a safety-sensitive location.")
                                add("Wait for a PO number before authorizing any 3rd party repairs.")
                            })
                        })
                        // Late for Appointment
                        add(buildJsonObject {
                            put("category", "Late for Appointment")
                            put("policy_summary", "Protocol for reporting delays to shippers or receivers.")
                            put("details", buildJsonArray {
                                add("If you anticipate being late for a pickup or delivery, you must send a Macro 22 (Late Arrival) via your tablet.")
                                add("Include the reason for the delay and your new Estimated Time of Arrival (ETA).")
                                add("Communicate delays as early as possible to allow Driver Managers to reschedule appointments.")
                            })
                        })
                    })
                }
            }

            "getPaycheckInfo" -> {
                // Placeholder for your paycheck tool
                buildJsonObject {
                    put("driver_id", DEMO_DRIVER_ID)
                    put("last_paycheck_date", "2026-04-11")
                    put("net_amount", 1450.25)
                    put("miles_paid", 2850)
                    put("cpm_rate", 0.52)
                }
            }
            
            "findNearestSwiftTerminal" -> {
                buildJsonObject {
                    put("driver_location", "I-40 EB near Flagstaff, AZ")
                    put("nearest_terminal", buildJsonObject {
                        put("name", "Jurupa Valley Terminal")
                        put("distance_miles", 385)
                        put("address", "11200 San Sevaine Way, Jurupa Valley, CA 91752")
                        put("amenities", buildJsonArray {
                            add("Open Showers")
                            add("Driver Lounge")
                            add("Full-Service Maintenance Shop")
                            add("Tractor/Trailer Wash")
                            add("Secure Parking")
                        })
                    })
                    put("alternative_drop_yard", buildJsonObject {
                        put("name", "Phoenix Terminal")
                        put("distance_miles", 145)
                        put("address", "2200 S 75th Ave, Phoenix, AZ 85043")
                        put("amenities", buildJsonArray {
                            add("Driver Lounge")
                            add("Maintenance Shop")
                            add("Fuel Island")
                        })
                    })
                }
            }
            
            "checkSafetyScore" -> {
                buildJsonObject {
                    put("driver_id", DEMO_DRIVER_ID)
                    put("current_score", 94.5)
                    put("status", "Green / Bonus Eligible")
                    put("company_percentile", "Top 15%")
                    put("recent_events", buildJsonArray {
                        add(buildJsonObject {
                            put("event_type", "Hard Braking")
                            put("date", "2026-04-14")
                            put("location", "I-40 near Kingman, AZ")
                            put("severity", "Moderate")
                            put("impact_on_score", "-1.5 pts")
                        })
                        add(buildJsonObject {
                            put("event_type", "Overspeed (>5mph)")
                            put("date", "2026-04-10")
                            put("location", "US-93 near Wickenburg, AZ")
                            put("severity", "Minor")
                            put("impact_on_score", "-0.5 pts")
                        })
                    })
                }
            }
            
            "getFuelNetworkRouting" -> {
                buildJsonObject {
                    put("driver_id", DEMO_DRIVER_ID)
                    put("current_fuel_level", "3/8 Tank")
                    put("recommended_stop", buildJsonObject {
                        put("brand", "Swift Fuel Network #AZ-17 / Pilot")
                        put("location", "Flagstaff, AZ (Exit 195)")
                        put("distance_miles", 12)
                        put("fuel_discount", "High")
                        put("amenities", buildJsonArray {
                            add("DEF at Pump")
                            add("Cat Scale")
                            add("Showers")
                            add("Restaurant")
                        })
                    })
                    put("restriction_warning", "Do NOT fuel at Love's or independent stops on this corridor; use only Pilot/Flying J or Swift yards.")
                }
            }

            else -> throw IllegalArgumentException("Unknown tool: $name")
        }
    }
}
