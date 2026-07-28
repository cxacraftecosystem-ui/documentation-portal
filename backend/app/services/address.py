"""Postal address rules: the Indian state / district lists, and the PIN code format.

WHY these two fields are validated server-side
----------------------------------------------
State and pincode are the address fields that go wrong in a way nothing downstream can repair.

A state typed as free text arrives as "Gujarat", "gujarat", "GJ", "Gujrat" and "gujarat " for one
state, and every group-by, filter, folder and export sheet then splits five ways for a single place.
This is not hypothetical here: ``services/text_format.py`` exists because exactly that happened to
craft names. The fix that holds for the web app, the Android app and any import script at the same
time is a CLOSED list resolved on write — which is what :func:`validate_state` does.

A pincode is worse, because a wrong one still looks right. Six digits are six digits; only the
format rules say whether they can be a real Indian PIN code. The first digit is the postal zone
(1–9), so a leading 0 is never issued, and anything that is not exactly six ASCII digits is a
transcription slip rather than an address.

WHY the list is served as well as enforced
------------------------------------------
:data:`INDIAN_STATES_AND_UNION_TERRITORIES` is the single source of truth, in two roles at once: the
Pydantic validators below reject anything outside it, and ``api/routes/reference.py`` serves the very
same tuple to the clients. That pairing is the point. A constant alone would still leave the web and
Android forms hard-coding their own copies, which is precisely how two lists drift apart until one
client can only submit values the server refuses. An endpoint alone would leave the server accepting
whatever a script felt like sending. Together, a form that renders its dropdown from the endpoint
cannot produce a value this module rejects, and the two can never disagree because there is only one
list.

Free-text spellings are still ACCEPTED where they are unambiguous — :data:`_ALIASES` covers the
renames (Orissa, Pondicherry, Uttaranchal), the 2020 UT merger, and the "&" spellings — because
records also arrive from import scripts that predate the dropdown. Two-letter codes ("GJ", "MP") are
deliberately NOT accepted: they are indistinguishable from a truncated name, and nothing that renders
the served list would ever send one.

WHY THE DISTRICT IS A CLOSED LIST TOO, AND WHY IT IS CHECKED AGAINST ITS STATE
------------------------------------------------------------------------------
Everything above applies harder to the district, because there are 795 of them and nobody holds all
their spellings. A free-text district splits "Kutch", "Kachchh", "Kachchh District" and "kutch " into
four rows of every group-by and four folders of every export, for one place.

What the district adds over the state is that it is only meaningful INSIDE a state. "Bilaspur" is a
district of Chhattisgarh and a different district of Himachal Pradesh. "Hamirpur" is in both Himachal
Pradesh and Uttar Pradesh. "Aurangabad" is a district of Bihar, and was the name of a Maharashtra
district until 2023. A flat list of district names cannot tell those apart and would happily record a
Chhattisgarh village in Himachal. So :data:`DISTRICTS_BY_STATE` is keyed BY STATE, and
:func:`validate_district` is given both halves: a district that belongs to another state is rejected
and told which state that is, because "wrong district" is not an error anybody can act on but
"Kachchh is in Gujarat, not Rajasthan" is.

PROVENANCE — where the district list comes from, and how to refresh it
-----------------------------------------------------------------------
India had ~640 districts at the 2011 census and has ~795 now; states create, merge, rename and
un-create them continually (Balotra, which appears in this repository's own records, did not exist
before August 2023). A district list with no source and no date cannot be reasoned about later, so
this one carries both — see :data:`DISTRICT_LIST_SOURCE` and :data:`DISTRICT_LIST_AS_OF`, which are
served to clients alongside the list itself.

  Base      The Local Government Directory (LGD) of the Ministry of Panchayati Raj, the register of
            record for Indian administrative units. Taken from the machine-readable mirror at
            github.com/planemad/india-local-government-directory (``administrative/2-district.csv``),
            whose district file was last updated 2022-12-14: 763 districts across all 36 states and
            union territories, each with its LGD district code.
  Amended   Every district notification since that snapshot, applied by hand and itemised in
            :data:`DISTRICT_AMENDMENTS` with the state and the date it took effect: +33, -1, giving
            795. The tests assert the amendments and the list agree, so neither can drift alone.
  Spelling  LGD's own spelling is kept except where it is not the name in ordinary use — LGD writes
            "24 Paraganas North", "Rudra Prayag", "East Nimar" and "Leh Ladakh". Those are corrected
            in the list and the LGD form is kept reachable as an alias, so a payload built from a raw
            LGD export still resolves.
  Checked   Per-state district counts were reconciled against Wikipedia's "List of districts in
            India" on 2026-07-26; all 36 agree.

To refresh:
  1. Re-pull ``administrative/2-district.csv`` from the mirror above, or export the district
     directory from lgdirectory.gov.in, which is the same data first-hand and republished monthly.
  2. Diff its district names per state against :data:`DISTRICTS_BY_STATE`.
  3. Decide each difference. A name only in the export is one we are missing. A name only here should
     have a row in :data:`DISTRICT_AMENDMENTS` justifying it — and if the export has caught up,
     delete that row and let the base carry the name instead.
  4. Bump :data:`DISTRICT_LIST_VERSION` and :data:`DISTRICT_LIST_AS_OF`; clients cache on that pair.
  5. Run the tests. They check that every district resolves to exactly one state, that every alias
     points at a name that exists, and that every name survives ``title_case`` unchanged.

RECONCILING A GEOCODER
----------------------
The reverse geocoder is a SEPARATE source of district names and does not spell them our way. MapTiler
returns the district as ``subregion`` (``county`` is the tehsil and is the natural wrong guess), and
returns it as "Jammu district", "Akola District" or "Kutch". :func:`reconcile_geocoded_district`
handles all three — a trailing administrative suffix is stripped, the fold absorbs the case and
punctuation, and :data:`_DISTRICT_ALIASES` covers the genuinely different spellings. Anything it
cannot resolve RAISES :class:`DistrictReconciliationError` rather than returning the raw string,
because a district written through as free text is exactly the outcome the closed list exists to
prevent, and a geocoder is the one caller that would otherwise do it silently and at scale.
"""

from __future__ import annotations

import re
from typing import Any

# The 28 states, alphabetically. Ordered for direct use as a dropdown, not sorted at call time, so
# the web and Android render the list in exactly the same order the server holds it.
INDIAN_STATES: tuple[str, ...] = (
    "Andhra Pradesh",
    "Arunachal Pradesh",
    "Assam",
    "Bihar",
    "Chhattisgarh",
    "Goa",
    "Gujarat",
    "Haryana",
    "Himachal Pradesh",
    "Jharkhand",
    "Karnataka",
    "Kerala",
    "Madhya Pradesh",
    "Maharashtra",
    "Manipur",
    "Meghalaya",
    "Mizoram",
    "Nagaland",
    "Odisha",
    "Punjab",
    "Rajasthan",
    "Sikkim",
    "Tamil Nadu",
    "Telangana",
    "Tripura",
    "Uttar Pradesh",
    "Uttarakhand",
    "West Bengal",
)

# The 8 union territories, alphabetically. Kept as their own tuple so a form can render a labelled
# "Union territories" group rather than burying Ladakh between Kerala and Madhya Pradesh.
INDIAN_UNION_TERRITORIES: tuple[str, ...] = (
    "Andaman and Nicobar Islands",
    "Chandigarh",
    "Dadra and Nagar Haveli and Daman and Diu",
    "Delhi",
    "Jammu and Kashmir",
    "Ladakh",
    "Lakshadweep",
    "Puducherry",
)

INDIAN_STATES_AND_UNION_TERRITORIES: tuple[str, ...] = INDIAN_STATES + INDIAN_UNION_TERRITORIES

# Every canonical name above is already a fixed point of ``text_format.title_case`` (verified against
# all 36), which matters because ``records.clean_data`` title-cases the ``state`` key on every write.
# A name that changed under that rule would be stored differently from the value this module serves,
# and the dropdown would stop matching what came back.

# Where the district list came from and when, carried as data rather than prose because it is served
# to the clients and belongs beside any dataset exported from them. See the PROVENANCE section above.
DISTRICT_LIST_SOURCE = (
    "Local Government Directory (LGD), Ministry of Panchayati Raj — district directory snapshot of "
    "2022-12-14, plus every district notification since, applied by hand"
)
DISTRICT_LIST_SOURCE_URL = "https://lgdirectory.gov.in/"
#: The day the list was last reconciled against its sources. Bump with any change to the list.
DISTRICT_LIST_AS_OF = "2026-07-26"
#: Bumped whenever the district list changes, so a client can cache on it. Independent of the
#: payload ``version`` in :func:`address_reference`, which also covers the state list and pincode.
DISTRICT_LIST_VERSION = 1

# Every difference between the LGD snapshot and the list below, one row per notification, so the next
# refresh can tell "we added this deliberately" from "the export is behind". Fields are the state, the
# change ("created" / "dissolved" / "renamed"), the district's name NOW, the name it replaces (only
# for a rename), and when it took effect. The tests check every row against DISTRICTS_BY_STATE, which
# is what stops this table becoming decoration.
DISTRICT_AMENDMENTS: tuple[tuple[str, str, str, str, str], ...] = (
    # Rajasthan created nineteen districts in 2023; the review notified 2024-12-28 scrapped nine and
    # kept these eight. Balotra is one of them, and appears in this repository's own records.
    ("Rajasthan", "created", "Balotra", "", "2023-08-07"),
    ("Rajasthan", "created", "Beawar", "", "2023-08-07"),
    ("Rajasthan", "created", "Deeg", "", "2023-08-07"),
    ("Rajasthan", "created", "Didwana-Kuchaman", "", "2023-08-07"),
    ("Rajasthan", "created", "Khairthal-Tijara", "", "2023-08-07"),
    ("Rajasthan", "created", "Kotputli-Behror", "", "2023-08-07"),
    ("Rajasthan", "created", "Phalodi", "", "2023-08-07"),
    ("Rajasthan", "created", "Salumbar", "", "2023-08-07"),
    # The 2021-22 Nagaland round. The snapshot caught Chümoukedima and Tseminyü from it but not these.
    ("Nagaland", "created", "Meluri", "", "2021-2022"),
    ("Nagaland", "created", "Niuland", "", "2021-2022"),
    ("Nagaland", "created", "Shamator", "", "2021-2022"),
    ("Arunachal Pradesh", "created", "Bichom", "", "2024-02"),
    ("Arunachal Pradesh", "created", "Keyi Panyor", "", "2024-02"),
    ("Madhya Pradesh", "created", "Maihar", "", "2023"),
    ("Madhya Pradesh", "created", "Mauganj", "", "2023"),
    ("Madhya Pradesh", "created", "Pandhurna", "", "2023"),
    # Announced August 2024, notified April 2026 — the announcement alone would have been too early
    # to list, which is the distinction this column exists to record.
    ("Ladakh", "created", "Changthang", "", "2026-04"),
    ("Ladakh", "created", "Drass", "", "2026-04"),
    ("Ladakh", "created", "Nubra", "", "2026-04"),
    ("Ladakh", "created", "Sham", "", "2026-04"),
    ("Ladakh", "created", "Zanskar", "", "2026-04"),
    ("Goa", "created", "Kushavati", "", "2025-12-31"),
    ("Haryana", "created", "Hansi", "", "2025"),
    ("Gujarat", "created", "Vav-Tharad", "", "2025-10-02"),
    ("Andhra Pradesh", "created", "Markapuram", "", "2025-12-31"),
    ("Andhra Pradesh", "created", "Polavaram", "", "2025-12-31"),
    ("West Bengal", "created", "Arambagh", "", "2026"),
    ("West Bengal", "created", "Basirhat", "", "2026"),
    ("West Bengal", "created", "Jangipur", "", "2026"),
    ("West Bengal", "created", "Sundarbans", "", "2026"),
    # Delhi realigned its revenue districts with the municipal zones: eleven became thirteen, and
    # Shahdara stopped existing rather than being renamed — its area went to several neighbours,
    # which is why there is no alias for it and an old value fails loudly instead of being guessed.
    ("Delhi", "created", "Central North", "", "2026-01-01"),
    ("Delhi", "created", "Old Delhi", "", "2026-01-01"),
    ("Delhi", "created", "Outer North", "", "2026-01-01"),
    ("Delhi", "dissolved", "Shahdara", "", "2026-01-01"),
    # Renames. The old name stays reachable through _DISTRICT_ALIASES — records predate the rename.
    ("Maharashtra", "renamed", "Chhatrapati Sambhajinagar", "Aurangabad", "2023-02"),
    ("Maharashtra", "renamed", "Dharashiv", "Osmanabad", "2023-02"),
    ("Maharashtra", "renamed", "Ahilyanagar", "Ahmednagar", "2024-10-05"),
    ("Assam", "renamed", "Sribhumi", "Karimganj", "2024-11"),
)

# The canonical districts of each state and union territory, alphabetically within the state, so the
# clients render a dropdown in exactly the order the server holds it — the same contract the state
# tuples above keep. Every name here is a fixed point of ``text_format.title_case``, which matters for
# the same reason it matters for the states: ``records.clean_data`` title-cases on write, and a name
# that changed under that rule would be stored differently from the value this module serves.
DISTRICTS_BY_STATE: dict[str, tuple[str, ...]] = {
    "Andaman and Nicobar Islands": (
        "Nicobars", "North and Middle Andaman", "South Andamans",
    ),
    "Andhra Pradesh": (
        "Alluri Sitharama Raju", "Anakapalli", "Anantapur", "Annamayya", "Bapatla", "Chittoor",
        "East Godavari", "Eluru", "Guntur", "Kakinada", "Konaseema", "Krishna", "Kurnool",
        "Markapuram", "NTR", "Nandyal", "Palnadu", "Parvathipuram Manyam", "Polavaram", "Prakasam",
        "SPSR Nellore", "Sri Sathya Sai", "Srikakulam", "Tirupati", "Visakhapatnam",
        "Vizianagaram", "West Godavari", "YSR Kadapa",
    ),
    "Arunachal Pradesh": (
        "Anjaw", "Bichom", "Changlang", "Dibang Valley", "East Kameng", "East Siang", "Kamle",
        "Keyi Panyor", "Kra Daadi", "Kurung Kumey", "Leparada", "Lohit", "Longding",
        "Lower Dibang Valley", "Lower Siang", "Lower Subansiri", "Namsai", "Pakke Kessang",
        "Papum Pare", "Shi Yomi", "Siang", "Tawang", "Tirap", "Upper Siang", "Upper Subansiri",
        "West Kameng", "West Siang",
    ),
    "Assam": (
        "Bajali", "Baksa", "Barpeta", "Biswanath", "Bongaigaon", "Cachar", "Charaideo", "Chirang",
        "Darrang", "Dhemaji", "Dhubri", "Dibrugarh", "Dima Hasao", "Goalpara", "Golaghat",
        "Hailakandi", "Hojai", "Jorhat", "Kamrup", "Kamrup Metropolitan", "Karbi Anglong",
        "Kokrajhar", "Lakhimpur", "Majuli", "Morigaon", "Nagaon", "Nalbari", "Sivasagar",
        "Sonitpur", "South Salmara Mancachar", "Sribhumi", "Tamulpur", "Tinsukia", "Udalguri",
        "West Karbi Anglong",
    ),
    "Bihar": (
        "Araria", "Arwal", "Aurangabad", "Banka", "Begusarai", "Bhagalpur", "Bhojpur", "Buxar",
        "Darbhanga", "Gaya", "Gopalganj", "Jamui", "Jehanabad", "Kaimur (Bhabua)", "Katihar",
        "Khagaria", "Kishanganj", "Lakhisarai", "Madhepura", "Madhubani", "Munger", "Muzaffarpur",
        "Nalanda", "Nawada", "Pashchim Champaran", "Patna", "Purbi Champaran", "Purnia", "Rohtas",
        "Saharsa", "Samastipur", "Saran", "Sheikhpura", "Sheohar", "Sitamarhi", "Siwan", "Supaul",
        "Vaishali",
    ),
    "Chandigarh": (
        "Chandigarh",
    ),
    "Chhattisgarh": (
        "Balod", "Baloda Bazar", "Balrampur", "Bastar", "Bemetara", "Bijapur", "Bilaspur",
        "Dantewada", "Dhamtari", "Durg", "Gariyaband", "Gaurella Pendra Marwahi", "Janjgir-Champa",
        "Jashpur", "Kabirdham", "Kanker", "Khairgarh Chhuikhadan Gandai", "Kondagaon", "Korba",
        "Korea", "Mahasamund", "Manendragarh Chirimiri Bharatpur", "Mohla Manpur Ambagarh Chouki",
        "Mungeli", "Narayanpur", "Raigarh", "Raipur", "Rajnandgaon", "Sakti",
        "Sarangarh Bilaigarh", "Sukma", "Surajpur", "Surguja",
    ),
    "Dadra and Nagar Haveli and Daman and Diu": (
        "Dadra and Nagar Haveli", "Daman", "Diu",
    ),
    "Delhi": (
        "Central", "Central North", "East", "New Delhi", "North", "North East", "North West",
        "Old Delhi", "Outer North", "South", "South East", "South West", "West",
    ),
    "Goa": (
        "Kushavati", "North Goa", "South Goa",
    ),
    "Gujarat": (
        "Ahmedabad", "Amreli", "Anand", "Aravalli", "Banaskantha", "Bharuch", "Bhavnagar", "Botad",
        "Chhota Udaipur", "Dahod", "Dangs", "Devbhumi Dwarka", "Gandhinagar", "Gir Somnath",
        "Jamnagar", "Junagadh", "Kachchh", "Kheda", "Mahisagar", "Mehsana", "Morbi", "Narmada",
        "Navsari", "Panchmahal", "Patan", "Porbandar", "Rajkot", "Sabarkantha", "Surat",
        "Surendranagar", "Tapi", "Vadodara", "Valsad", "Vav-Tharad",
    ),
    "Haryana": (
        "Ambala", "Bhiwani", "Charki Dadri", "Faridabad", "Fatehabad", "Gurugram", "Hansi",
        "Hisar", "Jhajjar", "Jind", "Kaithal", "Karnal", "Kurukshetra", "Mahendragarh", "Nuh",
        "Palwal", "Panchkula", "Panipat", "Rewari", "Rohtak", "Sirsa", "Sonipat", "Yamunanagar",
    ),
    "Himachal Pradesh": (
        "Bilaspur", "Chamba", "Hamirpur", "Kangra", "Kinnaur", "Kullu", "Lahul and Spiti", "Mandi",
        "Shimla", "Sirmaur", "Solan", "Una",
    ),
    "Jammu and Kashmir": (
        "Anantnag", "Bandipora", "Baramulla", "Budgam", "Doda", "Ganderbal", "Jammu", "Kathua",
        "Kishtwar", "Kulgam", "Kupwara", "Poonch", "Pulwama", "Rajouri", "Ramban", "Reasi",
        "Samba", "Shopian", "Srinagar", "Udhampur",
    ),
    "Jharkhand": (
        "Bokaro", "Chatra", "Deoghar", "Dhanbad", "Dumka", "East Singhbum", "Garhwa", "Giridih",
        "Godda", "Gumla", "Hazaribagh", "Jamtara", "Khunti", "Koderma", "Latehar", "Lohardaga",
        "Pakur", "Palamu", "Ramgarh", "Ranchi", "Sahebganj", "Saraikela Kharsawan", "Simdega",
        "West Singhbhum",
    ),
    "Karnataka": (
        "Bagalkote", "Ballari", "Belagavi", "Bengaluru Rural", "Bengaluru Urban", "Bidar",
        "Chamarajanagara", "Chikkaballapura", "Chikkamagaluru", "Chitradurga", "Dakshina Kannada",
        "Davangere", "Dharwad", "Gadag", "Hassan", "Haveri", "Kalaburagi", "Kodagu", "Kolar",
        "Koppal", "Mandya", "Mysuru", "Raichur", "Ramanagara", "Shivamogga", "Tumakuru", "Udupi",
        "Uttara Kannada", "Vijayanagar", "Vijayapura", "Yadgir",
    ),
    "Kerala": (
        "Alappuzha", "Ernakulam", "Idukki", "Kannur", "Kasaragod", "Kollam", "Kottayam",
        "Kozhikode", "Malappuram", "Palakkad", "Pathanamthitta", "Thiruvananthapuram", "Thrissur",
        "Wayanad",
    ),
    "Ladakh": (
        "Changthang", "Drass", "Kargil", "Leh", "Nubra", "Sham", "Zanskar",
    ),
    "Lakshadweep": (
        "Lakshadweep",
    ),
    "Madhya Pradesh": (
        "Agar Malwa", "Alirajpur", "Anuppur", "Ashoknagar", "Balaghat", "Barwani", "Betul",
        "Bhind", "Bhopal", "Burhanpur", "Chhatarpur", "Chhindwara", "Damoh", "Datia", "Dewas",
        "Dhar", "Dindori", "Guna", "Gwalior", "Harda", "Indore", "Jabalpur", "Jhabua", "Katni",
        "Khandwa", "Khargone", "Maihar", "Mandla", "Mandsaur", "Mauganj", "Morena", "Narmadapuram",
        "Narsinghpur", "Neemuch", "Niwari", "Pandhurna", "Panna", "Raisen", "Rajgarh", "Ratlam",
        "Rewa", "Sagar", "Satna", "Sehore", "Seoni", "Shahdol", "Shajapur", "Sheopur", "Shivpuri",
        "Sidhi", "Singrauli", "Tikamgarh", "Ujjain", "Umaria", "Vidisha",
    ),
    "Maharashtra": (
        "Ahilyanagar", "Akola", "Amravati", "Beed", "Bhandara", "Buldhana", "Chandrapur",
        "Chhatrapati Sambhajinagar", "Dharashiv", "Dhule", "Gadchiroli", "Gondia", "Hingoli",
        "Jalgaon", "Jalna", "Kolhapur", "Latur", "Mumbai", "Mumbai Suburban", "Nagpur", "Nanded",
        "Nandurbar", "Nashik", "Palghar", "Parbhani", "Pune", "Raigad", "Ratnagiri", "Sangli",
        "Satara", "Sindhudurg", "Solapur", "Thane", "Wardha", "Washim", "Yavatmal",
    ),
    "Manipur": (
        "Bishnupur", "Chandel", "Churachandpur", "Imphal East", "Imphal West", "Jiribam",
        "Kakching", "Kamjong", "Kangpokpi", "Noney", "Pherzawl", "Senapati", "Tamenglong",
        "Tengnoupal", "Thoubal", "Ukhrul",
    ),
    "Meghalaya": (
        "East Garo Hills", "East Jaintia Hills", "East Khasi Hills", "Eastern West Khasi Hills",
        "North Garo Hills", "Ri Bhoi", "South Garo Hills", "South West Garo Hills",
        "South West Khasi Hills", "West Garo Hills", "West Jaintia Hills", "West Khasi Hills",
    ),
    "Mizoram": (
        "Aizawl", "Champhai", "Hnahthial", "Khawzawl", "Kolasib", "Lawngtlai", "Lunglei", "Mamit",
        "Saiha", "Saitual", "Serchhip",
    ),
    "Nagaland": (
        "Chumoukedima", "Dimapur", "Kiphire", "Kohima", "Longleng", "Meluri", "Mokokchung", "Mon",
        "Niuland", "Noklak", "Peren", "Phek", "Shamator", "Tseminyu", "Tuensang", "Wokha",
        "Zunheboto",
    ),
    "Odisha": (
        "Anugul", "Balangir", "Baleshwar", "Bargarh", "Bhadrak", "Boudh", "Cuttack", "Deogarh",
        "Dhenkanal", "Gajapati", "Ganjam", "Jagatsinghapur", "Jajapur", "Jharsuguda", "Kalahandi",
        "Kandhamal", "Kendrapara", "Kendujhar", "Khordha", "Koraput", "Malkangiri", "Mayurbhanj",
        "Nabarangpur", "Nayagarh", "Nuapada", "Puri", "Rayagada", "Sambalpur", "Sonepur",
        "Sundargarh",
    ),
    "Puducherry": (
        "Karaikal", "Mahe", "Puducherry", "Yanam",
    ),
    "Punjab": (
        "Amritsar", "Barnala", "Bathinda", "Faridkot", "Fatehgarh Sahib", "Fazilka", "Ferozepur",
        "Gurdaspur", "Hoshiarpur", "Jalandhar", "Kapurthala", "Ludhiana", "Malerkotla", "Mansa",
        "Moga", "Pathankot", "Patiala", "Rupnagar", "Sahibzada Ajit Singh Nagar", "Sangrur",
        "Shahid Bhagat Singh Nagar", "Sri Muktsar Sahib", "Tarn Taran",
    ),
    "Rajasthan": (
        "Ajmer", "Alwar", "Balotra", "Banswara", "Baran", "Barmer", "Beawar", "Bharatpur",
        "Bhilwara", "Bikaner", "Bundi", "Chittorgarh", "Churu", "Dausa", "Deeg", "Dholpur",
        "Didwana-Kuchaman", "Dungarpur", "Ganganagar", "Hanumangarh", "Jaipur", "Jaisalmer",
        "Jalore", "Jhalawar", "Jhunjhunu", "Jodhpur", "Karauli", "Khairthal-Tijara", "Kota",
        "Kotputli-Behror", "Nagaur", "Pali", "Phalodi", "Pratapgarh", "Rajsamand", "Salumbar",
        "Sawai Madhopur", "Sikar", "Sirohi", "Tonk", "Udaipur",
    ),
    "Sikkim": (
        "Gangtok", "Gyalshing", "Mangan", "Namchi", "Pakyong", "Soreng",
    ),
    "Tamil Nadu": (
        "Ariyalur", "Chengalpattu", "Chennai", "Coimbatore", "Cuddalore", "Dharmapuri", "Dindigul",
        "Erode", "Kallakurichi", "Kanchipuram", "Kanniyakumari", "Karur", "Krishnagiri", "Madurai",
        "Mayiladuthurai", "Nagapattinam", "Namakkal", "Perambalur", "Pudukkottai",
        "Ramanathapuram", "Ranipet", "Salem", "Sivaganga", "Tenkasi", "Thanjavur", "The Nilgiris",
        "Theni", "Thiruvallur", "Thiruvarur", "Thoothukudi", "Tiruchirappalli", "Tirunelveli",
        "Tirupathur", "Tiruppur", "Tiruvannamalai", "Vellore", "Villupuram", "Virudhunagar",
    ),
    "Telangana": (
        "Adilabad", "Bhadradri Kothagudem", "Hanumakonda", "Hyderabad", "Jagitial", "Jangoan",
        "Jayashankar Bhupalapally", "Jogulamba Gadwal", "Kamareddy", "Karimnagar", "Khammam",
        "Kumuram Bheem Asifabad", "Mahabubabad", "Mahabubnagar", "Mancherial", "Medak",
        "Medchal Malkajgiri", "Mulugu", "Nagarkurnool", "Nalgonda", "Narayanpet", "Nirmal",
        "Nizamabad", "Peddapalli", "Rajanna Sircilla", "Ranga Reddy", "Sangareddy", "Siddipet",
        "Suryapet", "Vikarabad", "Wanaparthy", "Warangal", "Yadadri Bhuvanagiri",
    ),
    "Tripura": (
        "Dhalai", "Gomati", "Khowai", "North Tripura", "Sepahijala", "South Tripura", "Unakoti",
        "West Tripura",
    ),
    "Uttar Pradesh": (
        "Agra", "Aligarh", "Ambedkar Nagar", "Amethi", "Amroha", "Auraiya", "Ayodhya", "Azamgarh",
        "Baghpat", "Bahraich", "Ballia", "Balrampur", "Banda", "Barabanki", "Bareilly", "Basti",
        "Bhadohi", "Bijnor", "Budaun", "Bulandshahr", "Chandauli", "Chitrakoot", "Deoria", "Etah",
        "Etawah", "Farrukhabad", "Fatehpur", "Firozabad", "Gautam Buddha Nagar", "Ghaziabad",
        "Ghazipur", "Gonda", "Gorakhpur", "Hamirpur", "Hapur", "Hardoi", "Hathras", "Jalaun",
        "Jaunpur", "Jhansi", "Kannauj", "Kanpur Dehat", "Kanpur Nagar", "Kasganj", "Kaushambi",
        "Kheri", "Kushi Nagar", "Lalitpur", "Lucknow", "Maharajganj", "Mahoba", "Mainpuri",
        "Mathura", "Mau", "Meerut", "Mirzapur", "Moradabad", "Muzaffarnagar", "Pilibhit",
        "Pratapgarh", "Prayagraj", "Rae Bareli", "Rampur", "Saharanpur", "Sambhal",
        "Sant Kabeer Nagar", "Shahjahanpur", "Shamli", "Shravasti", "Siddharth Nagar", "Sitapur",
        "Sonbhadra", "Sultanpur", "Unnao", "Varanasi",
    ),
    "Uttarakhand": (
        "Almora", "Bageshwar", "Chamoli", "Champawat", "Dehradun", "Haridwar", "Nainital",
        "Pauri Garhwal", "Pithoragarh", "Rudraprayag", "Tehri Garhwal", "Udham Singh Nagar",
        "Uttarkashi",
    ),
    "West Bengal": (
        "Alipurduar", "Arambagh", "Bankura", "Basirhat", "Birbhum", "Cooch Behar",
        "Dakshin Dinajpur", "Darjeeling", "Hooghly", "Howrah", "Jalpaiguri", "Jangipur",
        "Jhargram", "Kalimpong", "Kolkata", "Malda", "Murshidabad", "Nadia", "North 24 Parganas",
        "Paschim Bardhaman", "Paschim Medinipur", "Purba Bardhaman", "Purba Medinipur", "Purulia",
        "South 24 Parganas", "Sundarbans", "Uttar Dinajpur",
    ),
}

DISTRICT_COUNT = sum(len(names) for names in DISTRICTS_BY_STATE.values())

PINCODE_LENGTH = 6

# Separators a person types inside a name or a pincode: spaces, hyphens, en/em dashes.
_SEPARATORS = re.compile(r"[\s‐-―-]+")
_NON_ALNUM = re.compile(r"[^a-z0-9]+")

# ASCII digits ONLY — deliberately not ``str.isdigit()``, which answers True for Devanagari "१२३" and
# every other decimal script. Those would pass a length check and be stored verbatim, giving one
# village two different pincodes that no query could ever match to each other. Same reasoning, and
# the same regex, as the Aadhaar validator in services/artisan_identity.py.
_ASCII_DIGITS = re.compile(r"[0-9]+")

# Spellings that are unambiguous but not canonical. Keys are already run through :func:`_fold`.
#
#   * Orissa / Pondicherry / Uttaranchal — official renames; historical records still use them.
#   * Dadra and Nagar Haveli / Daman and Diu — two separate UTs until they merged in 2020, so older
#     data names each half. Both resolve to the merged territory.
#   * The Delhi variants — the territory is written a dozen ways in official forms.
#
# "&" is folded to "and" before lookup, so no ampersand spelling needs an entry here.
_ALIASES: dict[str, str] = {
    "orissa": "Odisha",
    "pondicherry": "Puducherry",
    "pondichery": "Puducherry",
    "uttaranchal": "Uttarakhand",
    "chattisgarh": "Chhattisgarh",
    "dadraandnagarhaveli": "Dadra and Nagar Haveli and Daman and Diu",
    "damananddiu": "Dadra and Nagar Haveli and Daman and Diu",
    "newdelhi": "Delhi",
    "delhinct": "Delhi",
    "nctofdelhi": "Delhi",
    "nationalcapitalterritoryofdelhi": "Delhi",
}


# District spellings that are not canonical but are unambiguous INSIDE their state. Keyed by state
# because that is the only level at which a district name means one thing — "Bilaspur", "Hamirpur",
# "Raigarh/Raigad" and "Bijapur" each name districts in two different states.
#
# Unlike :data:`_ALIASES` the keys here are written as a person would type them and are folded at
# import. Hand-folding a hundred-odd entries is a bug farm, and a mis-folded key fails silently — it
# simply never matches. The tests check every value is a real district of its state.
#
# Four kinds of entry, all of which arrive in practice:
#   * geocoder output — "Kutch" for Kachchh is the one MapTiler actually returns;
#   * the LGD export's own spelling, so a payload built from the raw source still resolves
#     ("24 Paraganas North", "Rudra Prayag", "East Nimar", "Leh Ladakh");
#   * official renames, because records predate them (Allahabad, Gurgaon, Aurangabad, Karimganj);
#   * the colonial-era English names still printed on maps (Calicut, Trichy, Belgaum, Balasore).
#
# A dissolved district is deliberately NOT aliased. Delhi's Shahdara was split across several
# neighbours, so any single target would be a guess; it fails loudly instead.
_DISTRICT_ALIASES: dict[str, dict[str, str]] = {
    "Sikkim": {
        "East District": "Gangtok",
        "West District": "Gyalshing",
        "North District": "Mangan",
        "South District": "Namchi",
    },
    "Arunachal Pradesh": {
        "Upper Dibang Valley": "Dibang Valley",
    },
    "Andaman and Nicobar Islands": {
        "South Andaman": "South Andamans",
    },
    "Tripura": {
        "Sipahijala": "Sepahijala",
        "Unokoti": "Unakoti",
    },
    "Andhra Pradesh": {
        "Ananthapuramu": "Anantapur",
        "Cuddapah": "YSR Kadapa", "Kadapa": "YSR Kadapa", "Y.S.R.": "YSR Kadapa",
        "Nellore": "SPSR Nellore", "Sri Potti Sriramulu Nellore": "SPSR Nellore",
        "Visakhapatanam": "Visakhapatnam", "Vishakhapatnam": "Visakhapatnam",
        "Dr. B. R. Ambedkar Konaseema": "Konaseema",
        "Anantpur": "Anantapur",
        "Sri Potti Sriramulu Nell": "SPSR Nellore",
    },
    "Assam": {
        "Karimganj": "Sribhumi",
        "Kamrup Metro": "Kamrup Metropolitan",
        "Marigaon": "Morigaon",
        "Sibsagar": "Sivasagar",
        "South Salmara-Mankachar": "South Salmara Mancachar",
    },
    "Bihar": {
        "East Champaran": "Purbi Champaran", "Purba Champaran": "Purbi Champaran",
        "West Champaran": "Pashchim Champaran",
        "Kaimur": "Kaimur (Bhabua)", "Bhabua": "Kaimur (Bhabua)",
        "Purnea": "Purnia",
    },
    "Chhattisgarh": {
        "Dakshin Bastar Dantewada": "Dantewada",
        "Uttar Bastar Kanker": "Kanker",
        "Kawardha": "Kabirdham",
        "Koriya": "Korea",
        "Gaurela Pendra Marwahi": "Gaurella Pendra Marwahi",
        "Bametara": "Bemetara",
        "Gariaband": "Gariyaband",
        "Kabeerdham": "Kabirdham",
    },
    "Gujarat": {
        # The MapTiler spelling for Kachchh, and the one the researchers type.
        "Kutch": "Kachchh", "Kutchh": "Kachchh", "Kachh": "Kachchh",
        "Ahmadabad": "Ahmedabad",
        "Banas Kantha": "Banaskantha",
        "Sabar Kantha": "Sabarkantha",
        "Panch Mahals": "Panchmahal", "Panchmahals": "Panchmahal",
        "Dohad": "Dahod",
        "Mahesana": "Mehsana",
        "Chhotaudepur": "Chhota Udaipur", "Chhota Udepur": "Chhota Udaipur",
        "Arvalli": "Aravalli",
        "Dang": "Dangs", "The Dangs": "Dangs",
        "Devbhoomi Dwarka": "Devbhumi Dwarka",
        "Chota Udaipur": "Chhota Udaipur",
    },
    "Haryana": {
        "Mewat": "Nuh",
        "Gurgaon": "Gurugram",
        "Charkhi Dadri": "Charki Dadri",
    },
    "Himachal Pradesh": {
        "Lahaul and Spiti": "Lahul and Spiti",
    },
    "Jammu and Kashmir": {
        "Badgam": "Budgam",
        "Bandipore": "Bandipora",
        "Baramula": "Baramulla",
        "Shupiyan": "Shopian",
        "Punch": "Poonch",
    },
    "Jharkhand": {
        "East Singhbhum": "East Singhbum", "Purbi Singhbhum": "East Singhbum",
        "Pashchimi Singhbhum": "West Singhbhum",
        "Saraikela-Kharsawan": "Saraikela Kharsawan", "Seraikela Kharsawan": "Saraikela Kharsawan",
        "Sahibganj": "Sahebganj",
        "Hazaribag": "Hazaribagh",
        "Kodarma": "Koderma",
    },
    "Karnataka": {
        "Bangalore Urban": "Bengaluru Urban", "Bangalore Rural": "Bengaluru Rural",
        "Mysore": "Mysuru",
        "Belgaum": "Belagavi",
        "Gulbarga": "Kalaburagi",
        "Bellary": "Ballari",
        "Shimoga": "Shivamogga",
        "Bijapur": "Vijayapura",
        "Chikmagalur": "Chikkamagaluru",
        "Tumkur": "Tumakuru",
        "Bagalkot": "Bagalkote",
        "Chamarajanagar": "Chamarajanagara",
        "Chikballapur": "Chikkaballapura",
        "Vijayanagara": "Vijayanagar",
        "Bangalore": "Bengaluru Urban",
        "Davanagere": "Davangere",
    },
    "Kerala": {
        "Cannanore": "Kannur",
        "Alleppey": "Alappuzha",
        "Quilon": "Kollam",
        "Trichur": "Thrissur",
        "Calicut": "Kozhikode",
        "Palghat": "Palakkad",
        "Trivandrum": "Thiruvananthapuram",
    },
    "Ladakh": {
        "Leh Ladakh": "Leh",
        "Dras": "Drass",
        "Zangskar": "Zanskar",
    },
    "Lakshadweep": {
        "Lakshadweep District": "Lakshadweep",
    },
    "Madhya Pradesh": {
        "East Nimar": "Khandwa",
        "West Nimar": "Khargone",
        "Hoshangabad": "Narmadapuram",
        "Narsimhapur": "Narsinghpur",
    },
    "Maharashtra": {
        "Aurangabad": "Chhatrapati Sambhajinagar", "Sambhajinagar": "Chhatrapati Sambhajinagar",
        "Osmanabad": "Dharashiv",
        "Ahmednagar": "Ahilyanagar", "Ahmadnagar": "Ahilyanagar",
        "Bombay": "Mumbai",
        "Buldana": "Buldhana",
        "Gondiya": "Gondia",
        "Raigarh": "Raigad",
        "Bid": "Beed",
    },
    "Odisha": {
        "Angul": "Anugul",
        "Balasore": "Baleshwar",
        "Baudh": "Boudh",
        "Debagarh": "Deogarh",
        "Jajpur": "Jajapur",
        "Jagatsinghpur": "Jagatsinghapur",
        "Keonjhar": "Kendujhar",
        "Khurda": "Khordha",
        "Nabarangapur": "Nabarangpur",
        "Subarnapur": "Sonepur", "Sonapur": "Sonepur",
        "Sundergarh": "Sundargarh",
        "Bolangir": "Balangir",
        "Phulbani": "Kandhamal",
    },
    "Puducherry": {
        "Pondicherry": "Puducherry",
    },
    "Punjab": {
        "Mohali": "Sahibzada Ajit Singh Nagar", "SAS Nagar": "Sahibzada Ajit Singh Nagar",
        "Nawanshahr": "Shahid Bhagat Singh Nagar",
        "Muktsar": "Sri Muktsar Sahib",
        "Ropar": "Rupnagar",
        "Firozpur": "Ferozepur", "Ferozepore": "Ferozepur",
        "Bhatinda": "Bathinda",
        "Sahibzada Ajit Singh Nag": "Sahibzada Ajit Singh Nagar",
    },
    "Rajasthan": {
        "Sri Ganganagar": "Ganganagar", "Shri Ganganagar": "Ganganagar",
        "Jhunjhunun": "Jhunjhunu",
        "Jalor": "Jalore",
        "Chittaurgarh": "Chittorgarh",
        "Dhaulpur": "Dholpur",
        "Balotara": "Balotra",
    },
    "Tamil Nadu": {
        "Tuticorin": "Thoothukudi", "Thoothukkudi": "Thoothukudi",
        "Trichy": "Tiruchirappalli",
        "Kanyakumari": "Kanniyakumari",
        "Nilgiris": "The Nilgiris",
        "Sivagangai": "Sivaganga",
        "Tirupattur": "Tirupathur",
        "Villuppuram": "Villupuram",
        "Tiruvallur": "Thiruvallur",
        "Tiruvarur": "Thiruvarur",
        "Kallakurichchi": "Kallakurichi",
        "Tiruppattur": "Tirupathur",
        "Viluppuram": "Villupuram",
    },
    "Telangana": {
        "Jagtial": "Jagitial",
        "Jangaon": "Jangoan",
        "Rangareddy": "Ranga Reddy",
        "Komaram Bheem Asifabad": "Kumuram Bheem Asifabad",
        "Warangal Urban": "Hanumakonda",
        "Warangal Rural": "Warangal",
        "Jayashankar": "Jayashankar Bhupalapally",
    },
    "Uttar Pradesh": {
        "Allahabad": "Prayagraj",
        "Faizabad": "Ayodhya",
        "Sant Ravidas Nagar": "Bhadohi",
        "Mahamaya Nagar": "Hathras",
        "Jyotiba Phule Nagar": "Amroha",
        "Kanshiram Nagar": "Kasganj",
        "Bhim Nagar": "Sambhal",
        "Prabuddh Nagar": "Shamli",
        "Panchsheel Nagar": "Hapur",
        "Sant Kabir Nagar": "Sant Kabeer Nagar",
        "Kushinagar": "Kushi Nagar",
        "Siddharthnagar": "Siddharth Nagar",
        "Lakhimpur Kheri": "Kheri",
        "Raebareli": "Rae Bareli",
        "Badaun": "Budaun",
        "Sonebhadra": "Sonbhadra",
        "Gautam Buddh Nagar": "Gautam Buddha Nagar",
        "Shrawasti": "Shravasti",
        "Mahrajganj": "Maharajganj",
    },
    "Uttarakhand": {
        "Rudra Prayag": "Rudraprayag",
        "Udam Singh Nagar": "Udham Singh Nagar",
        "Uttar Kashi": "Uttarkashi",
        "Garhwal": "Pauri Garhwal",
        "Hardwar": "Haridwar",
        "Tehri": "Tehri Garhwal",
    },
    "West Bengal": {
        "24 Paraganas North": "North 24 Parganas", "24 Paraganas South": "South 24 Parganas",
        "Medinipur West": "Paschim Medinipur", "West Medinipur": "Paschim Medinipur",
        "Medinipur East": "Purba Medinipur", "East Medinipur": "Purba Medinipur",
        "Coochbehar": "Cooch Behar", "Koch Bihar": "Cooch Behar",
        "Maldah": "Malda",
        "Dinajpur Dakshin": "Dakshin Dinajpur", "South Dinajpur": "Dakshin Dinajpur",
        "Dinajpur Uttar": "Uttar Dinajpur", "North Dinajpur": "Uttar Dinajpur",
        "Hugli": "Hooghly",
        "Haora": "Howrah",
        "Puruliya": "Purulia",
        "Darjiling": "Darjeeling",
        "North Twenty Four Pargan": "North 24 Parganas",
        "South Twenty Four Pargana": "South 24 Parganas",
    },
}


def _fold(value: str) -> str:
    """A state name reduced to its comparison key: lower-cased, "&" spelled out, punctuation dropped.

    "Tamil Nadu", "TAMIL NADU", "tamil-nadu" and "Tamilnadu" all fold to ``tamilnadu``, so a value
    that is only mis-cased or mis-spaced is corrected rather than rejected. Folding "&" to "and"
    first is what makes "Jammu & Kashmir" and "Andaman & Nicobar Islands" resolve without needing an
    alias entry for every ampersand variant.
    """
    return _NON_ALNUM.sub("", value.lower().replace("&", "and"))


_LOOKUP: dict[str, str] = {
    **{_fold(name): name for name in INDIAN_STATES_AND_UNION_TERRITORIES},
    **_ALIASES,
}

# Administrative words a geocoder or a form-filler appends to a district name. MapTiler adds
# "district" in whichever case it feels like ("Jammu district", "Akola District") — the fold flattens
# the case, so only the word itself has to be listed. The transliterations of ज़िला are here because
# an Indic-keyboard entry romanises to one of them.
_DISTRICT_SUFFIXES: tuple[str, ...] = ("district", "dist", "zilla", "zila", "jilla", "jila")


def _district_keys(value: str) -> tuple[str, ...]:
    """The keys ``value`` may be found under: as folded, then with a trailing admin word removed.

    The unstripped key is tried FIRST so a real district always beats a speculative strip. Nothing on
    the current list ends in one of these words, but a future one might, and losing a canonical name
    to a suffix rule would be a silent wrong answer rather than a loud one.
    """
    folded = _fold(value)
    for suffix in _DISTRICT_SUFFIXES:
        if folded.endswith(suffix) and len(folded) > len(suffix):
            return (folded, folded[: -len(suffix)])
    return (folded,)


# state -> folded spelling -> canonical district. Canonical names are merged LAST so an alias can
# never shadow a real district: "Didwana Kuchaman" and "Didwana-Kuchaman" fold to the same key.
_DISTRICT_LOOKUP: dict[str, dict[str, str]] = {
    state: {
        **{_fold(alias): target for alias, target in _DISTRICT_ALIASES.get(state, {}).items()},
        **{_fold(name): name for name in districts},
    }
    for state, districts in DISTRICTS_BY_STATE.items()
}


def _build_district_states() -> dict[str, tuple[str, ...]]:
    """Folded district spelling -> the states that know it, so a wrong pairing can name the right one.

    Aliases are included on purpose: somebody who chooses Rajasthan and types "Kutch" should be told
    Gujarat, not merely that Rajasthan has no such district.
    """
    homes: dict[str, list[str]] = {}
    for state, index in _DISTRICT_LOOKUP.items():
        for key in index:
            homes.setdefault(key, []).append(state)
    return {key: tuple(states) for key, states in homes.items()}


_DISTRICT_STATES: dict[str, tuple[str, ...]] = _build_district_states()


def normalize_state(value: str | None) -> str | None:
    """Resolve any accepted spelling to its canonical name; ``None``/blank stays ``None``.

    An UNRECOGNISED value is returned trimmed rather than dropped, so :func:`state_error` can name it
    back to the person who typed it. Silently discarding it would save the record with a blank state
    and no explanation of what happened to what they entered.
    """
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    return _LOOKUP.get(_fold(text), text)


def state_error(value: str | None) -> str | None:
    """The reason ``value`` is not a state on the canonical list, or ``None`` when it is fine."""
    if value is None:
        return None
    # ``normalize_state`` has already resolved every accepted spelling, so anything that is not a
    # canonical name at this point is a name nobody recognises.
    if value in INDIAN_STATES_AND_UNION_TERRITORIES:
        return None
    return (
        f"'{value}' is not an Indian state or union territory. Choose one of the "
        f"{len(INDIAN_STATES_AND_UNION_TERRITORIES)} names offered by the state list."
    )


def validate_state(value: str | None) -> str | None:
    """Normalise and validate in one step; raises ``ValueError`` for a Pydantic field validator."""
    normalized = normalize_state(value)
    error = state_error(normalized)
    if error:
        raise ValueError(error)
    return normalized


class DistrictReconciliationError(ValueError):
    """A district name from an automatic source could not be resolved to the canonical list.

    Subclasses ``ValueError`` so it behaves like every other validator in this module inside a
    Pydantic field validator (a 422 naming the field), while still being catchable on its own by the
    geocoding path, which wants to log the raw value and flag the record rather than fail the write.
    """

    def __init__(self, message: str, *, state: str | None, value: str) -> None:
        super().__init__(message)
        #: The state the value was offered against, canonical where it could be resolved.
        self.state = state
        #: Exactly what the geocoder said, so it can be logged or shown beside the flag.
        self.value = value


def districts_for_state(state: str | None) -> tuple[str, ...]:
    """The canonical districts of ``state``; empty for an unknown state.

    Accepts any spelling :func:`normalize_state` accepts, so a caller holding "Gujrat" from an old
    import still gets the right list rather than nothing.
    """
    return DISTRICTS_BY_STATE.get(normalize_state(state) or "", ())


def normalize_district(state: str | None, value: str | None) -> str | None:
    """Resolve a district spelling to its canonical name within ``state``; blank stays ``None``.

    Like :func:`normalize_state`, an unrecognised value comes back TRIMMED rather than dropped, so
    :func:`district_error` can quote it back to whoever typed it. When the state itself is not on the
    canonical list there is nothing to resolve against, and the value is returned as given — the
    state is the error worth reporting, and :func:`district_error` reports it.
    """
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    index = _DISTRICT_LOOKUP.get(normalize_state(state) or "")
    if index is None:
        return text
    for key in _district_keys(text):
        found = index.get(key)
        if found:
            return found
    return text


def _name_states(states: tuple[str, ...]) -> str:
    """"Gujarat", or "Chhattisgarh or Himachal Pradesh" — for an error a person has to read."""
    if len(states) == 1:
        return states[0]
    return f"{', '.join(states[:-1])} or {states[-1]}"


def district_error(state: str | None, value: str | None) -> str | None:
    """The reason ``value`` is not a district of ``state``, or ``None`` when the pair is fine.

    Expects the value :func:`normalize_district` returned, exactly as :func:`state_error` expects the
    output of :func:`normalize_state`.

    The interesting case is the third one. A district that is real but belongs to a DIFFERENT state
    is the mistake this cross-field check exists to catch, and it is almost always a mis-picked state
    rather than a mis-typed district — so the message names the state the district actually belongs
    to instead of asking the researcher to go and look it up.
    """
    if value is None:
        return None
    resolved_state = normalize_state(state)
    if resolved_state is None:
        return (
            "Choose a state before the district — a district name only identifies one place inside "
            "its own state, and several are used by two states at once."
        )
    if resolved_state not in DISTRICTS_BY_STATE:
        # Not a state we hold districts for, so the district cannot be judged. Report the real fault.
        return state_error(resolved_state)
    if value in DISTRICTS_BY_STATE[resolved_state]:
        return None
    for key in _district_keys(value):
        elsewhere = tuple(s for s in _DISTRICT_STATES.get(key, ()) if s != resolved_state)
        if elsewhere:
            return (
                f"'{value}' is a district of {_name_states(elsewhere)}, not of {resolved_state}. "
                f"Change the state, or choose one of the "
                f"{len(DISTRICTS_BY_STATE[resolved_state])} districts of {resolved_state}."
            )
    return (
        f"'{value}' is not a district of {resolved_state}. Choose one of the "
        f"{len(DISTRICTS_BY_STATE[resolved_state])} districts the list offers for {resolved_state} "
        f"(district list as of {DISTRICT_LIST_AS_OF})."
    )


def validate_district(state: str | None, value: str | None) -> str | None:
    """Normalise and validate in one step; raises ``ValueError`` for a Pydantic model validator.

    A MODEL validator rather than a field one, because this rule reads two fields — the district
    cannot be judged without the state, which is the entire point of holding the list per state.
    """
    normalized = normalize_district(state, value)
    error = district_error(state, normalized)
    if error:
        raise ValueError(error)
    return normalized


def canonical_state(value: str | None) -> str | None:
    """The canonical state name, or ``None`` for anything not on the closed list.

    WHY THIS EXISTS BESIDE :func:`normalize_state`, which looks like it already does this. It does not,
    and the difference is a trap: ``normalize_state`` returns an unrecognised value TRIMMED rather than
    dropped, deliberately, so :func:`state_error` can quote it back to whoever typed it. That is right
    for a WRITE path, where the user must be told what happened to their input.

    It is wrong — silently — for a READ path that only wants to know "is this a real state". A caller
    that treats a truthy ``normalize_state`` as "valid" accepts "Atlantis", and downstream it becomes a
    map pin key, a group-by bucket and a district anchor for a state that does not exist. This pairs
    the normaliser with its validator so a reader cannot get that wrong by omission.

    Use :func:`validate_state` on writes (it raises, with the message), and this on reads.
    """
    normalized = normalize_state(value)
    if normalized is None or state_error(normalized):
        return None
    return normalized


def canonical_district(state: str | None, value: str | None) -> str | None:
    """The canonical district name WITHIN ``state``, or ``None`` when the pair is not real.

    The same distinction as :func:`canonical_state`, and it bites harder here because the check is
    cross-field: ``normalize_district("Rajasthan", "Kachchh")`` returns "Kachchh" — a real district, of
    Gujarat — because judging the pair is :func:`district_error`'s job, not the normaliser's. A read
    path that trusted the normaliser would happily build a "Rajasthan|Kachchh" district that exists
    nowhere, and average two states' records into it.
    """
    resolved_state = canonical_state(state)
    if resolved_state is None:
        return None
    normalized = normalize_district(resolved_state, value)
    if normalized is None or district_error(resolved_state, normalized):
        return None
    return normalized


def reconcile_geocoded_district(state: str | None, value: str | None) -> str | None:
    """Resolve a district name that came from a geocoder, or RAISE.

    MapTiler's ``subregion`` is the district (``county`` is the tehsil and is the wrong field), and it
    arrives as "Jammu district", "Akola District" or "Kutch" — a trailing administrative word, an
    inconsistent case, and a spelling that is simply different. All three resolve here.

    An EMPTY answer is not an error: ``subregion`` is legitimately absent out at sea and at a handful
    of rural points, and a geocoder that declines to guess is behaving correctly. ``None`` comes back
    and the caller leaves the field alone.

    Anything non-empty that will not resolve raises :class:`DistrictReconciliationError`. That is the
    whole point of routing geocoder output through here: automatic input is the one source that can
    quietly write hundreds of unreviewed free-text spellings into a closed column, and a value nobody
    can place has to stop and be looked at rather than be stored as though a researcher chose it.
    """
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    normalized = normalize_district(state, text)
    error = district_error(state, normalized)
    if error:
        raise DistrictReconciliationError(
            f"Geocoded district '{text}' could not be reconciled to the canonical district list. "
            f"{error}",
            state=normalize_state(state),
            value=text,
        )
    return normalized


def normalize_pincode(value: str | None) -> str | None:
    """"380 001" -> "380001". ``None``/blank stays ``None`` — the field is optional."""
    if value is None:
        return None
    cleaned = _SEPARATORS.sub("", str(value)).strip()
    return cleaned or None


def pincode_error(value: str | None) -> str | None:
    """The reason ``value`` is not a usable PIN code, or ``None`` when it is fine.

    Names the specific problem, the way the Aadhaar validator does, because "invalid pincode" tells a
    field researcher holding a handwritten address nothing about which digit to re-read.
    """
    if value is None:
        return None
    if not _ASCII_DIGITS.fullmatch(value):
        return "Pincode must be 6 digits — remove any letters or symbols."
    if len(value) != PINCODE_LENGTH:
        return f"Pincode must be exactly 6 digits (this one has {len(value)})."
    if value[0] == "0":
        # The leading digit is the postal zone, numbered 1–9. There is no zone 0, so a leading zero
        # is always a typo or a truncated number rather than a real address.
        return "Pincodes never start with 0 — please re-check the first digit."
    return None


def validate_pincode(value: str | None) -> str | None:
    """Normalise and validate in one step; raises ``ValueError`` for a Pydantic field validator."""
    normalized = normalize_pincode(value)
    error = pincode_error(normalized)
    if error:
        raise ValueError(error)
    return normalized


def address_reference() -> dict[str, Any]:
    """The state list, the district list and the pincode rule as JSON, for the clients' forms.

    ``version`` lets a client cache the payload and notice when the server's lists move on — a UT
    merger, a rename or a new district is a real event, not a hypothetical one. Nothing here is
    sensitive or per-user.

    THE DISTRICTS SHIP IN THIS SAME RESPONSE, deliberately. A state dropdown whose district dropdown
    needs a second request has a visible stall between the two on a rural connection, and the pair
    can momentarily disagree; one payload cannot.

    WHAT IT WEIGHS. ``byState`` is 795 districts as an object keyed by state, each value a plain
    array of strings — the most compact JSON this data has. (An array of ``{state, districts}``
    objects repeats two keys 36 times; one object per district repeats the state name 795 times and
    roughly triples the payload.) That is 9.9 KB of JSON, 4.4 KB on the wire once the CDN gzips it,
    against 1.2 KB for the rest of the response — 11.7 KB and 5.1 KB for the whole thing. One picture
    from the phone is a thousand times larger. The alias table is NOT served: it is input-side only,
    no dropdown needs it, and it would add half as much again for something no client renders.
    """
    return {
        "version": 2,
        "states": list(INDIAN_STATES),
        "unionTerritories": list(INDIAN_UNION_TERRITORIES),
        # The flat list a single-group dropdown binds to, in the same order as the two groups above.
        "statesAndUnionTerritories": list(INDIAN_STATES_AND_UNION_TERRITORIES),
        "districts": {
            # Served WITH the list, not just about it: a dataset exported from these records has to
            # be able to say which vintage of the district list it was coded against.
            "source": DISTRICT_LIST_SOURCE,
            "sourceUrl": DISTRICT_LIST_SOURCE_URL,
            "asOf": DISTRICT_LIST_AS_OF,
            "listVersion": DISTRICT_LIST_VERSION,
            "count": DISTRICT_COUNT,
            "byState": {state: list(names) for state, names in DISTRICTS_BY_STATE.items()},
            "normalisation": {
                "trailingWordsStripped": list(_DISTRICT_SUFFIXES),
                "description": (
                    "Districts are matched case- and punctuation-insensitively, with a trailing "
                    "administrative word removed, so 'Akola District' and 'akola' both resolve to "
                    "Akola. A district is only valid within its own state."
                ),
            },
        },
        "pincode": {
            "length": PINCODE_LENGTH,
            "pattern": "^[1-9][0-9]{5}$",
            "description": (
                "Exactly 6 digits. The first digit is the postal zone (1–9), so a pincode never "
                "starts with 0."
            ),
        },
    }
