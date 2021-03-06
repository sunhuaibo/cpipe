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
/**
 * Filter to find variants that differ between a number of VCFs
 * Writes an output VCF with a "DS" tag specifying which input files
 * the variant was found in.
 *
 * NOTE: Ignores sample information. Assumes each VCF contains 1 sample.
 * Requires Groovy-NGS-Utils (https://github.com/ssadedin/groovy-ngs-utils)
 */
import org.apache.commons.cli.Option

Cli cli = new Cli()
cli.with { 
        vcf 'VCFs to compare (supply multiple times)', args: Option.UNLIMITED_VALUES, required:true
}

opts = cli.parse(args)
if(!opts) 
        System.exit(1)

// Read all the VCFs
vcfs = opts.vcfs.collect { VCF.parse(it) }

// Create a super-VCF containing all the variants from all the VCFs
combined = new VCF(headerLines: vcfs[0].headerLines)
vcfs.each { vcf ->
  for(Variant v in vcf) { 
        combined.add(v)
  }
}

// Now write out the VCF containing only variants that differ between the three
// Note: ignore heterozygosity here.
VCF output = new VCF(headerLines:vcfs[0].headerLines)
for(Variant v in combined) {
    def foundIn = vcfs.collect { v in it }
    if(!foundIn.every()) {
        v.update {
          v.info.DS=foundIn.collect { it ? 1 : 0 }.join(",")     
        }
        output.add(v)
    } 
}

output.print()

