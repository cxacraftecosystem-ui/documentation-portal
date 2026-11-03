from __future__ import annotations

import re
from typing import Any

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

DISTRICT_LIST_SOURCE = (
    "Local Government Directory (LGD), Ministry of Panchayati Raj — district directory snapshot of "
    "2022-12-14, plus every district notification since, applied by hand"
)
DISTRICT_LIST_SOURCE_URL = "https://lgdirectory.gov.in/"
DISTRICT_LIST_AS_OF = "2026-07-26"
DISTRICT_LIST_VERSION = 1

DISTRICT_AMENDMENTS: tuple[tuple[str, str, str, str, str], ...] = (
    ("Rajasthan", "created", "Balotra", "", "2023-08-07"),
    ("Rajasthan", "created", "Beawar", "", "2023-08-07"),
    ("Rajasthan", "created", "Deeg", "", "2023-08-07"),
    ("Rajasthan", "created", "Didwana-Kuchaman", "", "2023-08-07"),
    ("Rajasthan", "created", "Khairthal-Tijara", "", "2023-08-07"),
    ("Rajasthan", "created", "Kotputli-Behror", "", "2023-08-07"),
    ("Rajasthan", "created", "Phalodi", "", "2023-08-07"),
    ("Rajasthan", "created", "Salumbar", "", "2023-08-07"),
    ("Nagaland", "created", "Meluri", "", "2021-2022"),
    ("Nagaland", "created", "Niuland", "", "2021-2022"),
    ("Nagaland", "created", "Shamator", "", "2021-2022"),
    ("Arunachal Pradesh", "created", "Bichom", "", "2024-02"),
    ("Arunachal Pradesh", "created", "Keyi Panyor", "", "2024-02"),
    ("Madhya Pradesh", "created", "Maihar", "", "2023"),
    ("Madhya Pradesh", "created", "Mauganj", "", "2023"),
    ("Madhya Pradesh", "created", "Pandhurna", "", "2023"),
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
    ("Delhi", "created", "Central North", "", "2026-01-01"),
    ("Delhi", "created", "Old Delhi", "", "2026-01-01"),
    ("Delhi", "created", "Outer North", "", "2026-01-01"),
    ("Delhi", "dissolved", "Shahdara", "", "2026-01-01"),
    ("Maharashtra", "renamed", "Chhatrapati Sambhajinagar", "Aurangabad", "2023-02"),
    ("Maharashtra", "renamed", "Dharashiv", "Osmanabad", "2023-02"),
    ("Maharashtra", "renamed", "Ahilyanagar", "Ahmednagar", "2024-10-05"),
    ("Assam", "renamed", "Sribhumi", "Karimganj", "2024-11"),
)

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

_SEPARATORS = re.compile(r"[\s‐-―-]+")
_NON_ALNUM = re.compile(r"[^a-z0-9]+")

_ASCII_DIGITS = re.compile(r"[0-9]+")

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
    return _NON_ALNUM.sub("", value.lower().replace("&", "and"))


_LOOKUP: dict[str, str] = {
    **{_fold(name): name for name in INDIAN_STATES_AND_UNION_TERRITORIES},
    **_ALIASES,
}

_DISTRICT_SUFFIXES: tuple[str, ...] = ("district", "dist", "zilla", "zila", "jilla", "jila")


def _district_keys(value: str) -> tuple[str, ...]:
    folded = _fold(value)
    for suffix in _DISTRICT_SUFFIXES:
        if folded.endswith(suffix) and len(folded) > len(suffix):
            return (folded, folded[: -len(suffix)])
    return (folded,)


_DISTRICT_LOOKUP: dict[str, dict[str, str]] = {
    state: {
        **{_fold(alias): target for alias, target in _DISTRICT_ALIASES.get(state, {}).items()},
        **{_fold(name): name for name in districts},
    }
    for state, districts in DISTRICTS_BY_STATE.items()
}


def _build_district_states() -> dict[str, tuple[str, ...]]:
    homes: dict[str, list[str]] = {}
    for state, index in _DISTRICT_LOOKUP.items():
        for key in index:
            homes.setdefault(key, []).append(state)
    return {key: tuple(states) for key, states in homes.items()}


_DISTRICT_STATES: dict[str, tuple[str, ...]] = _build_district_states()


def normalize_state(value: str | None) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    return _LOOKUP.get(_fold(text), text)


def state_error(value: str | None) -> str | None:
    if value is None:
        return None
    if value in INDIAN_STATES_AND_UNION_TERRITORIES:
        return None
    return (
        f"'{value}' is not an Indian state or union territory. Choose one of the "
        f"{len(INDIAN_STATES_AND_UNION_TERRITORIES)} names offered by the state list."
    )


def validate_state(value: str | None) -> str | None:
    normalized = normalize_state(value)
    error = state_error(normalized)
    if error:
        raise ValueError(error)
    return normalized


class DistrictReconciliationError(ValueError):
    def __init__(self, message: str, *, state: str | None, value: str) -> None:
        super().__init__(message)
        self.state = state
        self.value = value


def districts_for_state(state: str | None) -> tuple[str, ...]:
    return DISTRICTS_BY_STATE.get(normalize_state(state) or "", ())


def normalize_district(state: str | None, value: str | None) -> str | None:
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
    if len(states) == 1:
        return states[0]
    return f"{', '.join(states[:-1])} or {states[-1]}"


def district_error(state: str | None, value: str | None) -> str | None:
    if value is None:
        return None
    resolved_state = normalize_state(state)
    if resolved_state is None:
        return (
            "Choose a state before the district — a district name only identifies one place inside "
            "its own state, and several are used by two states at once."
        )
    if resolved_state not in DISTRICTS_BY_STATE:
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
    normalized = normalize_district(state, value)
    error = district_error(state, normalized)
    if error:
        raise ValueError(error)
    return normalized


def canonical_state(value: str | None) -> str | None:
    normalized = normalize_state(value)
    if normalized is None or state_error(normalized):
        return None
    return normalized


def canonical_district(state: str | None, value: str | None) -> str | None:
    resolved_state = canonical_state(state)
    if resolved_state is None:
        return None
    normalized = normalize_district(resolved_state, value)
    if normalized is None or district_error(resolved_state, normalized):
        return None
    return normalized


def reconcile_geocoded_district(state: str | None, value: str | None) -> str | None:
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
    if value is None:
        return None
    cleaned = _SEPARATORS.sub("", str(value)).strip()
    return cleaned or None


def pincode_error(value: str | None) -> str | None:
    if value is None:
        return None
    if not _ASCII_DIGITS.fullmatch(value):
        return "Pincode must be 6 digits — remove any letters or symbols."
    if len(value) != PINCODE_LENGTH:
        return f"Pincode must be exactly 6 digits (this one has {len(value)})."
    if value[0] == "0":
        return "Pincodes never start with 0 — please re-check the first digit."
    return None


def validate_pincode(value: str | None) -> str | None:
    normalized = normalize_pincode(value)
    error = pincode_error(normalized)
    if error:
        raise ValueError(error)
    return normalized


def address_reference() -> dict[str, Any]:
    return {
        "version": 2,
        "states": list(INDIAN_STATES),
        "unionTerritories": list(INDIAN_UNION_TERRITORIES),
        "statesAndUnionTerritories": list(INDIAN_STATES_AND_UNION_TERRITORIES),
        "districts": {
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
