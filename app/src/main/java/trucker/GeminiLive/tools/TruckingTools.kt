package trucker.geminilive.tools

import trucker.geminilive.network.FunctionDeclaration
import trucker.geminilive.network.Schema
import trucker.geminilive.network.Tool
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString

object TruckingTools {
    private const val DEMO_DRIVER_ID = "284145"
    private const val DEMO_ACTIVE_LOAD_ID = "902771"
    
    // Cache for static tool responses to avoid regenerating JSON on each call
    private val responseCache = mutableMapOf<String, JsonElement>()
    private val jsonSerializer = Json { ignoreUnknownKeys = true }
    
    /**
     * Pre-computes and caches all static tool responses at initialization.
     * Call this during app startup to warm the cache.
     */
    fun warmCache() {
        if (responseCache.isNotEmpty()) return
        
        // Pre-compute all static responses
        responseCache["getDriverProfile"] = buildDriverProfileResponse()
        responseCache["getLoadStatus"] = buildLoadStatusResponse()
        responseCache["getHoursOfServiceClocks"] = buildHosClocksResponse()
        responseCache["getTrafficAndWeather"] = buildTrafficWeatherResponse()
        responseCache["getCompanyFAQs"] = buildCompanyFaqsResponse()
        responseCache["getPaycheckInfo"] = buildPaycheckInfoResponse()
        responseCache["findNearestSwiftTerminal"] = buildTerminalResponse()
        responseCache["checkSafetyScore"] = buildSafetyScoreResponse()
        responseCache["getFuelNetworkRouting"] = buildFuelNetworkResponse()
        responseCache["getContacts"] = buildContactsResponse()
        responseCache["getNextLoadDetails"] = buildNextLoadResponse()
        responseCache["getMentorFAQs"] = buildMentorFaqsResponse()
        responseCache["getOwnerOperatorFAQs"] = buildOwnerOperatorFaqsResponse()
        responseCache["closeApp"] = buildCloseAppResponse()
        
        // Pre-compute dispatch inbox variants
        responseCache["getDispatchInbox_true"] = buildDispatchInboxResponse(true)
        responseCache["getDispatchInbox_false"] = buildDispatchInboxResponse(false)
    }
    
    /**
     * Clears the response cache. Call this if data needs to be refreshed.
     */
    fun clearCache() {
        responseCache.clear()
    }
    
    /**
     * Returns the approximate cache size in bytes for monitoring.
     */
    fun getCacheSizeBytes(): Int {
        return responseCache.values.sumOf { 
            jsonSerializer.encodeToString(it).length * 2 // UTF-16 chars
        }
    }

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
                name = "getHoursOfServiceClocks",
                description = "Returns deterministic Hours of Service (HOS) clock status and deadlines from pre-authenticated session context. Invocation condition: call when the driver asks about their available drive time, duty time, cycle time, or next required break.",
                parameters = Schema(
                    type = "object",
                    properties = emptyMap()
                )
            ),
            FunctionDeclaration(
                name = "getTrafficAndWeather",
                description = "Returns deterministic traffic and weather intelligence for the next 1 hour of the current route. Invocation condition: call when the driver asks about immediate road conditions, weather, or traffic ahead.",
                parameters = Schema(
                    type = "object",
                    properties = emptyMap()
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
                description = "Returns Swift Transportation FAQs bundles by category (Pet/Rider Policy, Macros, Running Late Procedure, Breakdown Protocol, Headset Recommendations, etc). Invocation condition: call when the driver asks company-policy/procedure questions not specific to a single load state.",
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
            ),
            FunctionDeclaration(
                name = "getContacts",
                description = "Returns contact information for Swift Transportation departments, Driver/Fleet Leaders, and support services. Invocation condition: call when the driver asks for phone numbers, how to reach dispatch, payroll, safety, breakdown, or their leader.",
                parameters = Schema(
                    type = "object",
                    properties = emptyMap()
                )
            ),
            FunctionDeclaration(
                name = "getNextLoadDetails",
                description = "Returns deterministic details for the next scheduled load (pre-dispatch) including pickup/delivery windows and estimated miles from pre-authenticated session context. Invocation condition: call when the driver asks about their next load, what they are doing after the current load, or for details on a pending dispatch.",
                parameters = Schema(
                    type = "object",
                    properties = emptyMap()
                )
            ),
            FunctionDeclaration(
                name = "getMentorFAQs",
                description = "Returns details about how to become a driver mentor, benefits of mentoring, and requirements. Invocation condition: call when the driver asks about becoming a mentor, training new drivers, or mentor pay.",
                parameters = Schema(
                    type = "object",
                    properties = emptyMap()
                )
            ),
            FunctionDeclaration(
                name = "getOwnerOperatorFAQs",
                description = "Returns details about becoming an owner-operator at Swift, including lease options, pay structure (percentage-based), and equipment perks. Invocation condition: call when the driver asks about owning their own truck, leasing, or becoming their own boss.",
                parameters = Schema(
                    type = "object",
                    properties = emptyMap()
                )
            ),
            FunctionDeclaration(
                name = "closeApp",
                description = "Closes the Swift Copilot application when the driver explicitly requests to exit, quit, or close the app. Invocation condition: call ONLY when the driver specifically says they want to close the app, exit the app, quit the app, or similar explicit requests to end the session. Do NOT call for general sign-offs or goodbyes.",
                parameters = Schema(
                    type = "object",
                    properties = emptyMap()
                )
            )
        )
    )

    fun handleToolCall(name: String, args: Map<String, JsonElement>?): JsonElement {
        // Warm cache if not already done
        if (responseCache.isEmpty()) {
            warmCache()
        }
        
        // Check cache for static responses first
        val cachedResponse = when (name) {
            "getDriverProfile" -> responseCache["getDriverProfile"]
            "getLoadStatus" -> responseCache["getLoadStatus"]
            "getHoursOfServiceClocks" -> responseCache["getHoursOfServiceClocks"]
            "getTrafficAndWeather" -> responseCache["getTrafficAndWeather"]
            "getCompanyFAQs" -> responseCache["getCompanyFAQs"]
            "getPaycheckInfo" -> responseCache["getPaycheckInfo"]
            "findNearestSwiftTerminal" -> responseCache["findNearestSwiftTerminal"]
            "checkSafetyScore" -> responseCache["checkSafetyScore"]
            "getFuelNetworkRouting" -> responseCache["getFuelNetworkRouting"]
            "getContacts" -> responseCache["getContacts"]
            "getNextLoadDetails" -> responseCache["getNextLoadDetails"]
            "getMentorFAQs" -> responseCache["getMentorFAQs"]
            "getOwnerOperatorFAQs" -> responseCache["getOwnerOperatorFAQs"]
            "closeApp" -> responseCache["closeApp"]
            "getDispatchInbox" -> {
                val unreadOnly = args?.get("unreadOnly")?.jsonPrimitive?.booleanOrNull ?: false
                responseCache["getDispatchInbox_$unreadOnly"]
            }
            else -> null
        }
        
        if (cachedResponse != null) {
            return cachedResponse
        }
        
        // Fall back to dynamic generation (should not reach here for static tools)
        return generateToolResponse(name, args)
    }
    
    private fun generateToolResponse(name: String, args: Map<String, JsonElement>?): JsonElement {
        return when (name) {
            "getDriverProfile" -> buildDriverProfileResponse()
            "getLoadStatus" -> buildLoadStatusResponse()
            "getHoursOfServiceClocks" -> buildHosClocksResponse()
            "getTrafficAndWeather" -> buildTrafficWeatherResponse()
            "getDispatchInbox" -> {
                val unreadOnly = args?.get("unreadOnly")?.jsonPrimitive?.booleanOrNull ?: false
                buildDispatchInboxResponse(unreadOnly)
            }
            "getCompanyFAQs" -> buildCompanyFaqsResponse()
            "getPaycheckInfo" -> buildPaycheckInfoResponse()
            "findNearestSwiftTerminal" -> buildTerminalResponse()
            "checkSafetyScore" -> buildSafetyScoreResponse()
            "getFuelNetworkRouting" -> buildFuelNetworkResponse()
            "getContacts" -> buildContactsResponse()
            "getNextLoadDetails" -> buildNextLoadResponse()
            "getMentorFAQs" -> buildMentorFaqsResponse()
            "getOwnerOperatorFAQs" -> buildOwnerOperatorFaqsResponse()
            "closeApp" -> buildCloseAppResponse()
            else -> throw IllegalArgumentException("Unknown tool: $name")
        }
    }
    
    // Private builder functions for each response type
    
    private fun buildDriverProfileResponse(): JsonElement = buildJsonObject {
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
    
    private fun buildLoadStatusResponse(): JsonElement = buildJsonObject {
        put("driver_id", DEMO_DRIVER_ID)
        put("load_id", DEMO_ACTIVE_LOAD_ID)
        put("status", "in_transit")
        put("priority", "high")
        put("customer", buildJsonObject {
            put("name", "Walmart DC #213")
            put("swift_csr_phone", "800-800-2200")
        })
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
    
    private fun buildHosClocksResponse(): JsonElement = buildJsonObject {
        put("driver_id", DEMO_DRIVER_ID)
        put("clocks", buildJsonObject {
            put("drive_time_remaining", "5h 15m")
            put("duty_time_remaining", "8h 45m")
            put("cycle_time_remaining", "18h 45m")
            put("next_break_due_in", "2h 30m")
        })
        put("alerts", buildJsonArray {
            add(buildJsonObject {
                put("category", "HOS")
                put("severity", "warning")
                put("message", "11-hour drive limit projected in 5h 15m.")
                put("due_by", "2026-04-15T20:05")
            })
        })
    }
    
    private fun buildTrafficWeatherResponse(): JsonElement = buildJsonObject {
        put("driver_id", DEMO_DRIVER_ID)
        put("time_horizon", "1 hour")
        put("generated_at", "2026-04-15T14:20")
        put("conditions", buildJsonArray {
            add(buildJsonObject {
                put("type", "weather")
                put("impact", "High winds")
                put("segment", "I-40 EB near Holbrook")
                put("severity", "medium")
                put("recommended_action", "Reduce speed and maintain firm grip on steering wheel.")
            })
            add(buildJsonObject {
                put("type", "traffic")
                put("impact", "Slow moving traffic")
                put("segment", "I-40 EB mm 185-190")
                put("severity", "low")
                put("recommended_action", "Expect 5-10 minute delay.")
            })
        })
    }
    
    private fun buildDispatchInboxResponse(unreadOnly: Boolean): JsonElement {
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
        return buildJsonObject { put("messages", filteredMessages) }
    }
    
    private fun buildCompanyFaqsResponse(): JsonElement = buildJsonObject {
        put("company", "Swift Transportation")
        put("last_updated", "2026-04-10")
        put("categories", buildJsonArray {
            add(buildJsonObject {
                put("category", "Pet Policy")
                put("policy_summary", "Swift allows company drivers to bring one dog, weighing 40 pounds or less.")
                put("details", buildJsonArray {
                    add("Pet Type: Dogs only (no cats, birds, or exotic animals).")
                    add("Weight Restriction: Maximum 40 pounds.")
                    add("Required Documentation: Valid vaccination records and a rabies certificate.")
                    add("Approval Process: Terminal leader approval is mandatory.")
                    add("Pets must be well-behaved and house-trained.")
                })
            })
            add(buildJsonObject {
                put("category", "Rider Policy")
                put("policy_summary", "Authorized riders are permitted with a valid permit.")
                put("details", buildJsonArray {
                    add("Riders must be at least 12 years old.")
                    add("A small monthly insurance fee is deducted from pay.")
                    add("Permits must be renewed annually.")
                })
            })
            add(buildJsonObject {
                put("category", "Breakdown SOP")
                put("policy_summary", "Protocol for mechanical issues on the road.")
                put("details", buildJsonArray {
                    add("Use the 'Breakdown' macro on your tablet immediately.")
                    add("Call On-Road Support (Option 4) if you are in a safety-sensitive location.")
                    add("Wait for a PO number before authorizing any 3rd party repairs.")
                })
            })
            add(buildJsonObject {
                put("category", "Late for Appointment")
                put("policy_summary", "Protocol for reporting delays to shippers or receivers.")
                put("details", buildJsonArray {
                    add("If you anticipate being late for a pickup or delivery, you must send a Macro 22 (Late Arrival) via your tablet.")
                    add("Include the reason for the delay and your new Estimated Time of Arrival (ETA).")
                    add("Communicate delays as early as possible to allow Driver Managers to reschedule appointments.")
                })
            })
            add(buildJsonObject {
                put("category", "Common Tablet Macros")
                put("policy_summary", "Quick reference guide for standard in-cab communication macros.")
                put("details", buildJsonArray {
                    add("Macro 1: Arrived at Shipper/Pickup.")
                    add("Macro 2: Loaded and Leaving Shipper.")
                    add("Macro 3: Arrived at Consignee/Delivery.")
                    add("Macro 4: Empty and Available for Dispatch.")
                    add("Macro 8: Request Home Time.")
                    add("Macro 15: Request Cash Advance (for tolls/lumper).")
                    add("Macro 22: Running Late / ETA Update.")
                    add("Macro 55: On-Road Breakdown Report.")
                })
            })
            add(buildJsonObject {
                put("category", "Headset Recommendations")
                put("policy_summary", "FMCSA regulations require hands-free devices. Headsets must leave one ear open to hear emergency signals.")
                put("details", buildJsonArray {
                    add("BlueParrott B450-XT: 96% noise cancellation, 24h battery, 300ft range. Price: $160 - $180.")
                    add("Blue Tiger Elite Ultra 2.0: 96% noise cancellation, 60h talk time. Price: $210 - $230.")
                })
            })
        })
    }
    
    private fun buildPaycheckInfoResponse(): JsonElement = buildJsonObject {
        put("driver_id", DEMO_DRIVER_ID)
        put("last_paycheck_date", "2026-04-11")
        put("net_amount", 1450.25)
        put("miles_paid", 2850)
        put("cpm_rate", 0.52)
    }
    
    private fun buildTerminalResponse(): JsonElement = buildJsonObject {
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
    
    private fun buildSafetyScoreResponse(): JsonElement = buildJsonObject {
        put("driver_id", DEMO_DRIVER_ID)
        put("current_score", 945)
        put("status", "Green / Bonus Eligible")
        put("company_percentile", "Top 15%")
        put("recent_events", buildJsonArray {
            add(buildJsonObject {
                put("event_type", "Hard Braking")
                put("date", "2026-04-14")
                put("location", "I-40 near Kingman, AZ")
                put("severity", "Moderate")
                put("impact_on_score", "-3 pts")
            })
            add(buildJsonObject {
                put("event_type", "Overspeed (>5mph)")
                put("date", "2026-04-10")
                put("location", "US-93 near Wickenburg, AZ")
                put("severity", "Minor")
                put("impact_on_score", "-1 pts")
            })
        })
    }
    
    private fun buildFuelNetworkResponse(): JsonElement = buildJsonObject {
        put("driver_id", DEMO_DRIVER_ID)
        put("recommended_stop", buildJsonObject {
            put("brand", "Pilot")
            put("location", "Flagstaff, AZ (Exit 195)")
            put("distance_miles", 12)
            put("fuel_discount", "High")
            put("amenities", buildJsonArray {
                add("DEF at Pump")
                add("Cat Scale")
                add("Showers/Clean Bathrooms")
                add("Restaurant")
            })
        })
        put("restriction_warning", "Do NOT fuel at independent stops on this corridor; use only Pilot/Flying J/Love's or Swift yards.")
    }
    
    private fun buildContactsResponse(): JsonElement = buildJsonObject {
        put("driver_id", DEMO_DRIVER_ID)
        put("driver_leader", buildJsonObject {
            put("name", "Sarah Jenkins")
            put("contact_method", "In-cab Macro or Driver Portal")
            put("phone", "(602) 269-9700 ext 4561 (Phoenix Terminal)")
            put("availability", "Mon-Fri 0800-1700")
        })
        put("fleet_leader", buildJsonObject {
            put("name", "Marcus Reynolds (Dry Van OTR)")
            put("contact_method", "In-cab Macro or Driver Portal")
        })
        put("departments", buildJsonArray {
            add(buildJsonObject {
                put("name", "Driver Support Services (24/7)")
                put("phone", "800-555-0199")
                put("function", "Urgent on-road needs, dispatch issues, routing")
            })
            add(buildJsonObject {
                put("name", "Driver Placement")
                put("phone", "866-588-5264")
                put("function", "Change fleets or discuss placement")
            })
            add(buildJsonObject {
                put("name", "Corporate Headquarters (Phoenix)")
                put("phone", "(602) 269-9700")
                put("address", "2200 South 75th Avenue, Phoenix, AZ 85043")
            })
            add(buildJsonObject {
                put("name", "On-Road Breakdown Support")
                put("phone", "800-555-0188")
                put("function", "Mechanical issues, repairs, authorization")
            })
            add(buildJsonObject {
                put("name", "Payroll")
                put("phone", "800-555-0177")
            })
        })
    }
    
    private fun buildNextLoadResponse(): JsonElement = buildJsonObject {
        put("driver_id", DEMO_DRIVER_ID)
        put("load_id", "902812")
        put("status", "pending_dispatch")
        put("customer", buildJsonObject {
            put("name", "Atlanta Distribution Center (Target)")
            put("swift_csr_phone", "800-800-2200")
        })
        put("origin", "Dallas, TX")
        put("destination", "Atlanta, GA")
        put("pickup_window", "2026-04-16T15:00 to 2026-04-16T19:00")
        put("delivery_window", "2026-04-18T08:00 to 2026-04-18T12:00")
        put("total_miles", 780)
        put("equipment_required", "53ft Dry Van")
        put("notes", "High value load, no unauthorized stops.")
    }
    
    private fun buildMentorFaqsResponse(): JsonElement = buildJsonObject {
        put("program_name", "Swift Driver Mentor Program")
        put("overview", "Pass along your knowledge to the next generation of drivers while enhancing your own earning potential.")
        put("benefits", buildJsonArray {
            add("Boost earning potential: Top 25% of mentors make $100,000 annually.")
            add("Build connections and take control of your career path.")
            add("Share valuable knowledge with new drivers.")
        })
        put("requirements", buildJsonArray {
            add("Class A CDL.")
            add("Solid, safe driving record.")
            add("Certain amount of time on the road (determined by leadership).")
            add("Approval from Driver Leader, Terminal Leader, and Safety Leader.")
        })
        put("steps_to_join", buildJsonArray {
            add("Step 1: Talk with your Driver Leader about joining the program.")
            add("Step 2: Apply online via the Driver Portal.")
            add("Step 3: Enroll in Swift University's 'Driver Certification Mentor Program' upon approval.")
        })
        put("faqs", buildJsonArray {
            add(buildJsonObject {
                put("question", "How do I know if I qualify?")
                put("answer", "Your Driver Leader, Terminal Leader, and Safety Leader will let you know based on your experience and record.")
            })
            add(buildJsonObject {
                put("question", "Do I have to train all the time?")
                put("answer", "Full-time training is welcome but not required. You can train part-time and drive solo in between students.")
            })
            add(buildJsonObject {
                put("question", "Should I be concerned about personal space?")
                put("answer", "Discuss personal boundaries and cab etiquette with new students. Be courteous and allow for personal space.")
            })
            add(buildJsonObject {
                put("question", "Can I mentor even though I don't drive OTR?")
                put("answer", "Yes, most lines of business at Swift have mentors. Talk with leadership to confirm.")
            })
        })
    }
    
    private fun buildOwnerOperatorFaqsResponse(): JsonElement = buildJsonObject {
        put("program_name", "Swift Owner-Operator Program")
        put("value_proposition", "Unlock your entrepreneurial spirit and take control of your destiny by becoming your own boss.")
        put("financial_perks", buildJsonArray {
            add("No credit checks: Accessible regardless of credit history.")
            add("$0 down lease options: Start your journey without upfront costs.")
            add("Percentage-based pay: 70% of the market rate for each load.")
            add("Fuel surcharges: Based on the national average to keep net fuel costs stable.")
        })
        put("equipment_and_tech", buildJsonArray {
            add("Late model trucks: Freightliner, Kenworth, Peterbilt, Volvo, and International.")
            add("Opti-Idle & Heating: Optimal fuel savings and cab comfort.")
            add("Tech suite: Geotab tablets and Netradyne outward-facing dash cameras.")
            add("Customization: Personalized decal package options.")
        })
        put("support_network", buildJsonArray {
            add("Owner-Operator Load Board: View and book your own loads (Dry OTR).")
            add("24/7 On-Road support and Rapid Response team.")
            add("Access to 50+ shops and terminals with free parking and reduced shop rates ($80/hr).")
        })
        put("how_to_start", buildJsonObject {
            put("action", "Contact a Recruiter")
            put("phone", "877-772-1846")
            put("text", "602-559-1675")
        })
    }
    
    private fun buildCloseAppResponse(): JsonElement = buildJsonObject {
        put("action", "close_app")
        put("message", "Closing Swift Copilot as requested. Safe travels!")
    }
}




