-- Subjects and sub-subjects. A topic with no parent_id is a subject (Math); a topic with one is a
-- sub-subject (Algebra 2). Picking a subject gets you videos from it and all of its sub-subjects.
-- Only two levels: the feed doesn't look further down than one level, so never give a
-- sub-subject its own children.
--
-- The original 10 topics from V3 become subjects, so everyone's saved interests keep working.
-- Sub-subjects are inserted in learning order (Algebra 1 before Algebra 2), and the topic list
-- shows them in id order, so add new ones in the spot they belong.

ALTER TABLE topics
    ADD COLUMN parent_id INT REFERENCES topics (id) ON DELETE CASCADE,
    ADD CONSTRAINT topics_not_own_parent CHECK (parent_id <> id);

CREATE INDEX topics_parent_id_idx ON topics (parent_id);

INSERT INTO topics (name, search_query) VALUES
    ('Earth Science', 'earth science explained'),
    ('English',       'english language arts explained'),
    ('Languages',     'learn a language basics'),
    ('Art',           'art lesson explained'),
    ('Music',         'music lesson explained'),
    ('Health',        'health science explained'),
    ('Philosophy',    'philosophy explained'),
    ('Government',    'government and civics explained'),
    ('Engineering',   'engineering explained'),
    ('Business',      'business basics explained');

-- Names must be unique across all topics, since they're shown on their own in the picker.
INSERT INTO topics (parent_id, name, search_query)
SELECT subject.id, sub.name, sub.search_query
FROM (VALUES
    (1,  'Math',          'Arithmetic',                     'arithmetic explained'),
    (2,  'Math',          'Pre-Algebra',                    'pre-algebra explained'),
    (3,  'Math',          'Algebra 1',                      'algebra 1 explained'),
    (4,  'Math',          'Geometry',                       'geometry math explained'),
    (5,  'Math',          'Algebra 2',                      'algebra 2 explained'),
    (6,  'Math',          'Trigonometry',                   'trigonometry explained'),
    (7,  'Math',          'Precalculus',                    'precalculus explained'),
    (8,  'Math',          'Calculus',                       'calculus explained'),
    (9,  'Math',          'Statistics',                     'statistics explained'),
    (10, 'Math',          'Linear Algebra',                 'linear algebra explained'),

    (11, 'Programming',   'Python',                         'python programming explained'),
    (12, 'Programming',   'JavaScript',                     'javascript programming explained'),
    (13, 'Programming',   'Java',                           'java programming explained'),
    (14, 'Programming',   'Web Development',                'web development explained'),
    (15, 'Programming',   'Data Structures and Algorithms', 'data structures and algorithms explained'),
    (16, 'Programming',   'Databases',                      'databases sql explained'),
    (17, 'Programming',   'Cybersecurity',                  'cybersecurity explained'),
    (18, 'Programming',   'Machine Learning',               'machine learning explained'),

    (19, 'Physics',       'Mechanics',                      'classical mechanics physics explained'),
    (20, 'Physics',       'Waves and Sound',                'waves and sound physics explained'),
    (21, 'Physics',       'Electricity and Magnetism',      'electricity and magnetism explained'),
    (22, 'Physics',       'Thermodynamics',                 'thermodynamics explained'),
    (23, 'Physics',       'Relativity',                     'relativity physics explained'),
    (24, 'Physics',       'Quantum Physics',                'quantum physics explained'),

    (25, 'Chemistry',     'Atoms and the Periodic Table',   'atoms periodic table explained'),
    (26, 'Chemistry',     'Chemical Reactions',             'chemical reactions explained'),
    (27, 'Chemistry',     'Acids and Bases',                'acids and bases chemistry explained'),
    (28, 'Chemistry',     'Organic Chemistry',              'organic chemistry explained'),
    (29, 'Chemistry',     'Biochemistry',                   'biochemistry explained'),

    (30, 'Biology',       'Cell Biology',                   'cell biology explained'),
    (31, 'Biology',       'Genetics',                       'genetics explained'),
    (32, 'Biology',       'Evolution',                      'evolution biology explained'),
    (33, 'Biology',       'Human Anatomy',                  'human anatomy explained'),
    (34, 'Biology',       'Microbiology',                   'microbiology explained'),
    (35, 'Biology',       'Ecology',                        'ecology explained'),

    (36, 'Space',         'Solar System',                   'solar system explained'),
    (37, 'Space',         'Stars and Galaxies',             'stars and galaxies explained'),
    (38, 'Space',         'Cosmology',                      'cosmology universe explained'),
    (39, 'Space',         'Space Exploration',              'space exploration explained'),

    (40, 'History',       'Ancient History',                'ancient history explained'),
    (41, 'History',       'Medieval History',               'medieval history explained'),
    (42, 'History',       'US History',                     'us history explained'),
    (43, 'History',       'World War I',                    'world war 1 explained'),
    (44, 'History',       'World War II',                   'world war 2 explained'),
    (45, 'History',       'Modern History',                 'modern history explained'),

    (46, 'Economics',     'Microeconomics',                 'microeconomics explained'),
    (47, 'Economics',     'Macroeconomics',                 'macroeconomics explained'),
    (48, 'Economics',     'Personal Finance',               'personal finance explained'),
    (49, 'Economics',     'Investing',                      'investing basics explained'),

    (50, 'Psychology',    'Cognitive Psychology',           'cognitive psychology explained'),
    (51, 'Psychology',    'Developmental Psychology',       'developmental psychology explained'),
    (52, 'Psychology',    'Social Psychology',              'social psychology explained'),
    (53, 'Psychology',    'Mental Health',                  'mental health explained'),

    (54, 'Geography',     'Physical Geography',             'physical geography explained'),
    (55, 'Geography',     'Human Geography',                'human geography explained'),
    (56, 'Geography',     'World Cultures',                 'world cultures explained'),

    (57, 'Earth Science', 'Geology',                        'geology explained'),
    (58, 'Earth Science', 'Weather and Climate',            'weather and climate explained'),
    (59, 'Earth Science', 'Oceans',                         'oceanography explained'),
    (60, 'Earth Science', 'Natural Disasters',              'natural disasters explained'),

    (61, 'English',       'Grammar',                        'english grammar explained'),
    (62, 'English',       'Vocabulary',                     'english vocabulary explained'),
    (63, 'English',       'Writing',                        'how to write essays explained'),
    (64, 'English',       'Literature',                     'literature analysis explained'),
    (65, 'English',       'Poetry',                         'poetry explained'),

    (66, 'Languages',     'Spanish',                        'learn spanish basics'),
    (67, 'Languages',     'French',                         'learn french basics'),
    (68, 'Languages',     'German',                         'learn german basics'),
    (69, 'Languages',     'Japanese',                       'learn japanese basics'),
    (70, 'Languages',     'Mandarin Chinese',               'learn mandarin chinese basics'),
    (71, 'Languages',     'American Sign Language',         'learn american sign language basics'),

    (72, 'Art',           'Drawing',                        'drawing lesson explained'),
    (73, 'Art',           'Painting',                       'painting lesson explained'),
    (74, 'Art',           'Art History',                    'art history explained'),
    (75, 'Art',           'Photography',                    'photography basics explained'),
    (76, 'Art',           'Graphic Design',                 'graphic design explained'),

    (77, 'Music',         'Music Theory',                   'music theory explained'),
    (78, 'Music',         'Piano',                          'piano lesson basics'),
    (79, 'Music',         'Guitar',                         'guitar lesson basics'),
    (80, 'Music',         'Music History',                  'music history explained'),

    (81, 'Health',        'Nutrition',                      'nutrition explained'),
    (82, 'Health',        'Fitness',                        'exercise science explained'),
    (83, 'Health',        'Sleep',                          'sleep science explained'),
    (84, 'Health',        'First Aid',                      'first aid explained'),

    (85, 'Philosophy',    'Logic',                          'logic philosophy explained'),
    (86, 'Philosophy',    'Ethics',                         'ethics philosophy explained'),
    (87, 'Philosophy',    'Ancient Philosophy',             'ancient philosophy explained'),
    (88, 'Philosophy',    'Modern Philosophy',              'modern philosophy explained'),

    (89, 'Government',    'Civics',                         'civics explained'),
    (90, 'Government',    'US Government',                  'us government explained'),
    (91, 'Government',    'Law',                            'law explained'),
    (92, 'Government',    'International Relations',        'international relations explained'),

    (93, 'Engineering',   'Mechanical Engineering',         'mechanical engineering explained'),
    (94, 'Engineering',   'Electrical Engineering',         'electrical engineering explained'),
    (95, 'Engineering',   'Civil Engineering',              'civil engineering explained'),
    (96, 'Engineering',   'Robotics',                       'robotics explained'),

    (97, 'Business',      'Entrepreneurship',               'entrepreneurship explained'),
    (98, 'Business',      'Marketing',                      'marketing explained'),
    (99, 'Business',      'Accounting',                     'accounting basics explained'),
    (100, 'Business',     'Management',                     'management explained')
) AS sub (position, subject, name, search_query)
JOIN topics subject ON subject.name = sub.subject AND subject.parent_id IS NULL
ORDER BY sub.position;
