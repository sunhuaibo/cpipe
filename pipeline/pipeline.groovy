// vim: ts=4:sw=4:expandtab:cindent
/////////////////////////////////////////////////////////////////////////////////
//
// This file is part of Cpipe.
// 
// Cpipe is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, under version 3 of the License, subject
// to additional terms compatible with the GNU General Public License version 3,
// specified in the LICENSE file that is part of the Cpipe distribution.
//
// Cpipe is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of 
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with Cpipe.  If not, see <http://www.gnu.org/licenses/>.
//
/////////////////////////////////////////////////////////////////////////////////
//
// Cpipe Main Pipeline Script
//
/////////////////////////////////////////////////////////////////////////////////

about title: "Melbourne Genomics Demonstration Project Pipeline"

// Load the default configuration
load 'config.groovy'

// Local file can set EXOME_TARGET and ANALYSIS_PROFILES
if(file("../target_regions.txt").exists())  {
    load '../target_regions.txt'
}

requires EXOME_TARGET : """
        The exome target regions. This should be the whole regions targeted
        for capture by the exome kit. Note that the regions for analysis
        may be a subset, but you should always specify the whole exome
        region here.
    """

// remove spaces from gene lists and point to a new sample metadata file
// note that this isn't run through bpipe
correct_sample_metadata_file = {
    def target = new File('results')
    if( !target.exists() ) {
        target.mkdirs()
    }
    [ "sh", "-c", "python $SCRIPTS/correct_sample_metadata_file.py < $it > results/samples.corrected" ].execute().waitFor()
    return "results/samples.corrected"
}

sample_metadata_file = correct_sample_metadata_file(args[0]) // fix syntax issues and update sample_metadata_file

try {
  sample_info = SampleInfo.parse_mg_sample_info(sample_metadata_file)
}
catch (RuntimeException e) {
  sample_info = SampleInfo.parse_sample_info(sample_metadata_file)
}

// We are specifying that each analysis takes place inside a fixed file structure
// where the parent directory is named according to the batch name. Thus we
// can infer the batch name from the name of the parent directory.
// 
// Note: this variable can be overridden by passing a parameter to bpipe in case
// you are running in a different location.
batch = new File("..").canonicalFile.name

// Extract the analysis profiles from the sample information
ANALYSIS_PROFILES = sample_info*.value*.target as Set

all_samples = sample_info.keySet()

// all the core pipeline stages in the pipeline
load 'pipeline_stage_initialize.groovy' // preparation of a batch
load 'pipeline_stage_alignment.groovy' // generate a bam
load 'pipeline_stage_variant_calling.groovy' // find variants
load 'pipeline_stage_variant_analysis.groovy' // filter, normalize, annotate, post process
load 'pipeline_stage_reports.groovy'

// specific to type of analysis
load 'pipeline_stage_germline.groovy'
load 'pipeline_stage_somatic.groovy'
load 'pipeline_stage_trio.groovy'

run {
    initialize_batch_run + // some overall checks, overall target region, ped files, pipeline run ID

    // for each analysis profile we run the main pipeline in parallel
    ANALYSIS_PROFILES * 
    [
        initialize_profiles + // setup target regions
        
        all_samples * // for each sample...
        [
            // --- module 1. data pre-processing for each sample: ---
            // the goal of this module is an analysis ready BAM
            // alignment, mark duplicates, indel realignment, base recalibration -> analysis ready reads
            // loosely based on gatk workflow
            analysis_ready_reads + // pipeline_stages_alignment

            // --- module 2. variant discovery
            // the goal of this module is a raw VCF
            // each sample is passed onto each type of enabled analysis and processed if relevant
            [ 
                trio_analysis_phase_1, // (not yet implemented) haplotypecaller -> for samples to be analyzed in a trio
                somatic_analysis_phase_1, // not yet implemented
                germline_analysis_phase_1 // haplotypecaller + genotypegvcf -> for standalone samples to be analyzed
            ] +

            // generate reports and do checks based on bam
            analysis_ready_reports +
            analysis_ready_checks
        ] +

        // each type of analysis can do a second phase after all phase 1 stages have finished
        // i.e. because trio analysis is dependent on germline analysis phase 1
        all_samples *
        [
            trio_analysis_phase_2, // genotypegvcf
            somatic_analysis_phase_2,
            germline_analysis_phase_2
        ] +

        // --- module 3. fitering and annotation
        // given a raw vcf, filter, normalize, annotate, convert to lovd format
        all_samples *
        [
            variant_analysis // extract_gatk_table_parameters
        ]

        // qc_excel_report deprecated, see analysis_ready_reports
   ] +

   // produce the output spreadsheet, 1 per analysis profile
   // ANALYSIS_PROFILES * 
   // [ 
       // post_analysis_phase_1 // reports (currently does nothing)
   // ] +

   // Produce a mini bam for each variant to help investigate individual variants
   all_samples * 
   [ 
       post_analysis // reports
   ] +

   // And then finally write the provenance report (1 per sample)
   all_samples *
   [ 
       provenance_report
   ] +

   // clean up
   finish_batch_run
}

