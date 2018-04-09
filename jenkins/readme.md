This directory is for CI scripts. It should be updated from https://git.epam.com/dip-roles/hybris-scaffolding
 - git_utils.py: is a script for manage labels in git. All parameters could be reached by git_utils.py -h
 - pipeline: directory for storing jenkins groovy pipelines scripts
 - templates: directory for html templates for buildkit, release notes
 - buildkit_html_generator.py - generate index html files for biuldkit, contains git branches
 - git_html_report.py - create html report with release notes
 - git_out_of_date.py - send email with out-of-date branches to developers